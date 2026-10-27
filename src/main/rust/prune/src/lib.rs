//! Native statement summaries of bundle chunks for pruning, the Vite plugin's
//! prune.js `summarize` over oxc's Rust AST: parsing and summarizing in Rust
//! skips turning the AST into JavaScript objects, most of pruning's time.
//!
//! The summaries are prune.js': each top-level statement's UTF-16 `start` and
//! `end`, its `kind` (`root`, `decl` with the `names` it declares, `prop` for
//! `X.P = ...` / `X.prototype.P = ...` with its `object` and cljs$ `prop`),
//! the identifiers it references (`ids`) and the cljs$ properties it reads
//! (`props`), as the ESTree walk of prune.js `collect` finds them.

use napi::bindgen_prelude::Uint32Array;
use napi_derive::napi;
use oxc_allocator::Allocator;
use oxc_ast::ast::*;
use oxc_ast_visit::{walk, Visit};
use oxc_parser::Parser;
use oxc_span::{GetSpan, SourceType};
use rayon::prelude::*;
use rustc_hash::FxHashMap;

const NONE: u32 = u32::MAX;
const KIND_ROOT: u32 = 0;
const KIND_DECL: u32 = 1;
const KIND_PROP: u32 = 2;

fn is_cljs_prop(name: &str) -> bool {
    name.starts_with("cljs$")
}

/// JavaScript's `\s`.
fn is_js_space(c: char) -> bool {
    matches!(
        c,
        '\t' | '\n' | '\u{b}' | '\u{c}' | '\r' | ' ' | '\u{a0}' | '\u{1680}' | '\u{2000}'..='\u{200a}'
            | '\u{2028}' | '\u{2029}' | '\u{202f}' | '\u{205f}' | '\u{3000}' | '\u{feff}'
    )
}

/// Whether the 24 UTF-16 code units of `source` before byte offset `start`
/// end with `__PURE__*/`, whitespace, an optional `(` and whitespace: prune.js
/// `/__PURE__\*\/\s*\(?\s*$/` on `source.slice(start - 24, start)`.
fn pure_annotated(source: &str, start: usize) -> bool {
    let before = &source[..start];
    // the window of (at most) 24 UTF-16 units, whole characters
    let mut units = 0;
    let mut from = before.len();
    for (i, c) in before.char_indices().rev() {
        if units + c.len_utf16() > 24 {
            break;
        }
        units += c.len_utf16();
        from = i;
    }
    let window = before[from..].trim_end_matches(is_js_space);
    let window = window.strip_suffix('(').unwrap_or(window).trim_end_matches(is_js_space);
    window.ends_with("__PURE__*/")
}

fn unwrap<'b, 'a>(mut e: &'b Expression<'a>) -> &'b Expression<'a> {
    while let Expression::ParenthesizedExpression(p) = e {
        e = &p.expression;
    }
    e
}

/// prune.js `isPureInit`.
fn is_pure_init(e: Option<&Expression>, source: &str) -> bool {
    let Some(e) = e else { return true };
    let e = unwrap(e);
    let args_pure = |args: &[Argument]| {
        args.iter().all(|a| match a {
            Argument::SpreadElement(_) => false,
            a => is_pure_init(Some(a.to_expression()), source),
        })
    };
    match e {
        Expression::CallExpression(c) => {
            pure_annotated(source, c.span.start as usize) && args_pure(&c.arguments)
        }
        Expression::FunctionExpression(_)
        | Expression::ArrowFunctionExpression(_)
        | Expression::ClassExpression(_)
        | Expression::BooleanLiteral(_)
        | Expression::NullLiteral(_)
        | Expression::NumericLiteral(_)
        | Expression::BigIntLiteral(_)
        | Expression::RegExpLiteral(_)
        | Expression::StringLiteral(_)
        | Expression::Identifier(_)
        | Expression::TemplateLiteral(_) => true,
        Expression::NewExpression(n) => args_pure(&n.arguments),
        Expression::ObjectExpression(o) => o.properties.iter().all(|p| match p {
            ObjectPropertyKind::ObjectProperty(p) => !p.computed && is_pure_init(Some(&p.value), source),
            ObjectPropertyKind::SpreadProperty(_) => false,
        }),
        Expression::ArrayExpression(a) => a.elements.iter().all(|el| match el {
            ArrayExpressionElement::Elision(_) => true,
            ArrayExpressionElement::SpreadElement(_) => false,
            el => is_pure_init(Some(el.to_expression()), source),
        }),
        Expression::UnaryExpression(u) => is_pure_init(Some(&u.argument), source),
        Expression::StaticMemberExpression(m) => is_pure_init(Some(&m.object), source),
        Expression::PrivateFieldExpression(m) => is_pure_init(Some(&m.object), source),
        _ => false,
    }
}

/// A member expression's object and property, as ESTree's MemberExpression:
/// (object, Some(name)) when not computed, (object, None) when computed.
fn member<'b, 'a>(m: &'b MemberExpression<'a>) -> (&'b Expression<'a>, Option<&'b str>) {
    match m {
        MemberExpression::StaticMemberExpression(m) => (&m.object, Some(m.property.name.as_str())),
        MemberExpression::PrivateFieldExpression(m) => (&m.object, Some(m.field.name.as_str())),
        MemberExpression::ComputedMemberExpression(m) => (&m.object, None),
    }
}

struct Strings<'a> {
    index: FxHashMap<&'a str, u32>,
    table: Vec<&'a str>,
}

impl<'a> Strings<'a> {
    fn id(&mut self, s: &'a str) -> u32 {
        if let Some(&i) = self.index.get(s) {
            return i;
        }
        let i = self.table.len() as u32;
        self.table.push(s);
        self.index.insert(s, i);
        i
    }
}

/// prune.js `collect`: the identifiers and cljs$ properties of a statement.
struct Collect<'a, 's> {
    strings: &'s mut Strings<'a>,
    ids: Vec<u32>,
    props: Vec<u32>,
    // the statement each string was last added for, dedups ids and props
    id_seen: Vec<u32>,
    prop_seen: Vec<u32>,
    stamp: u32,
    unsupported: bool,
}

impl<'a> Collect<'a, '_> {
    fn add(list: &mut Vec<u32>, seen: &mut Vec<u32>, stamp: u32, i: u32) {
        let i = i as usize;
        if seen.len() <= i {
            seen.resize(i + 1, 0);
        }
        if seen[i] != stamp {
            seen[i] = stamp;
            list.push(i as u32);
        }
    }
    fn id(&mut self, name: &'a str) {
        let i = self.strings.id(name);
        Self::add(&mut self.ids, &mut self.id_seen, self.stamp, i);
    }
    fn prop(&mut self, name: &'a str) {
        let i = self.strings.id(name);
        Self::add(&mut self.props, &mut self.prop_seen, self.stamp, i);
    }
}

impl<'a> Visit<'a> for Collect<'a, '_> {
    fn visit_identifier_reference(&mut self, it: &IdentifierReference<'a>) {
        self.id(it.name.as_str());
    }
    fn visit_binding_identifier(&mut self, it: &BindingIdentifier<'a>) {
        self.id(it.name.as_str());
    }
    fn visit_identifier_name(&mut self, it: &IdentifierName<'a>) {
        self.id(it.name.as_str());
    }
    fn visit_label_identifier(&mut self, it: &LabelIdentifier<'a>) {
        self.id(it.name.as_str());
    }
    fn visit_string_literal(&mut self, it: &StringLiteral<'a>) {
        match it.value.as_str() {
            Some(v) if is_cljs_prop(v) => self.prop(v),
            Some(_) => {}
            // a lone surrogate, which a Rust string can't hold: the JavaScript
            // summarizer decides
            None => self.unsupported = true,
        }
    }
    // ESTree's MetaProperty, two Identifiers
    fn visit_import_meta(&mut self, _it: &ImportMeta) {
        self.id("import");
        self.id("meta");
    }
    fn visit_new_target(&mut self, _it: &NewTarget) {
        self.id("new");
        self.id("target");
    }
    // a property name isn't a reference to the binding of the same name
    fn visit_static_member_expression(&mut self, it: &StaticMemberExpression<'a>) {
        if is_cljs_prop(it.property.name.as_str()) {
            self.prop(it.property.name.as_str());
        }
        self.visit_expression(&it.object);
    }
    fn visit_private_field_expression(&mut self, it: &PrivateFieldExpression<'a>) {
        if is_cljs_prop(it.field.name.as_str()) {
            self.prop(it.field.name.as_str());
        }
        self.visit_expression(&it.object);
    }
    fn visit_object_property(&mut self, it: &ObjectProperty<'a>) {
        if it.computed {
            self.visit_property_key(&it.key);
        }
        self.visit_expression(&it.value);
    }
    fn visit_binding_property(&mut self, it: &BindingProperty<'a>) {
        if it.computed {
            self.visit_property_key(&it.key);
        }
        self.visit_binding_pattern(&it.value);
    }
    fn visit_assignment_target_property_property(&mut self, it: &AssignmentTargetPropertyProperty<'a>) {
        if it.computed {
            self.visit_property_key(&it.name);
        }
        self.visit_assignment_target_maybe_default(&it.binding);
    }
}

/// prune.js `propAssignment`: `X.P = ...`, `X.prototype.P = ...` or
/// `X.prototype[k] = ...`, (object, cljs$ prop, computed key, rhs).
fn prop_assignment<'b, 'a>(
    stmt: &'b Statement<'a>,
) -> Option<(&'b str, Option<&'b str>, Option<&'b Expression<'a>>, &'b Expression<'a>)> {
    let Statement::ExpressionStatement(s) = stmt else { return None };
    let Expression::AssignmentExpression(e) = unwrap(&s.expression) else { return None };
    if e.operator != AssignmentOperator::Assign {
        return None;
    }
    let lhs = e.left.as_member_expression()?;
    let (mut obj, prop) = member(lhs);
    if let Some(m) = obj.as_member_expression() {
        if let (o, Some("prototype")) = member(m) {
            obj = o;
        }
    }
    let Expression::Identifier(obj) = obj else { return None };
    let computed = match lhs {
        MemberExpression::ComputedMemberExpression(m) => Some(&m.expression),
        _ => None,
    };
    Some((obj.name.as_str(), prop.filter(|p| is_cljs_prop(p)), computed, &e.right))
}

/// prune.js `defonceDecl`: `if (typeof X !== "undefined") {} else var X = <pure>;`
fn defonce_decl<'b, 'a>(stmt: &'b Statement<'a>, source: &str) -> Option<&'b str> {
    let Statement::IfStatement(s) = stmt else { return None };
    let alternate = s.alternate.as_ref()?;
    let Expression::BinaryExpression(t) = unwrap(&s.test) else { return None };
    let Expression::UnaryExpression(u) = unwrap(&t.left) else { return None };
    if u.operator != UnaryOperator::Typeof {
        return None;
    }
    let Expression::Identifier(arg) = &u.argument else { return None };
    let Statement::BlockStatement(cons) = &s.consequent else { return None };
    if !cons.body.is_empty() {
        return None;
    }
    let alt = match alternate {
        Statement::BlockStatement(b) if b.body.len() == 1 => &b.body[0],
        alt => alt,
    };
    let Statement::VariableDeclaration(v) = alt else { return None };
    if v.declarations.len() != 1 {
        return None;
    }
    let d = &v.declarations[0];
    let BindingPattern::BindingIdentifier(id) = &d.id else { return None };
    (id.name == arg.name && is_pure_init(d.init.as_ref(), source)).then_some(arg.name.as_str())
}

/// UTF-16 offsets of increasing byte offsets of source.
struct Utf16<'s> {
    bytes: &'s [u8],
    byte: usize,
    unit: u32,
}

impl Utf16<'_> {
    fn at(&mut self, byte: u32) -> u32 {
        let byte = byte as usize;
        if byte < self.byte {
            // not increasing: count from the start
            self.byte = 0;
            self.unit = 0;
        }
        for &b in &self.bytes[self.byte..byte] {
            // a character's first byte: 1 unit, 2 for 4-byte (astral) ones
            if b & 0xC0 != 0x80 {
                self.unit += if b >= 0xF0 { 2 } else { 1 };
            }
        }
        self.byte = byte;
        self.unit
    }
}

/// Statement summaries being written, see summarize.
struct Summaries<'a> {
    strings: Strings<'a>,
    out: Vec<u32>,
    utf16: Utf16<'a>,
    id_seen: Vec<u32>,
    prop_seen: Vec<u32>,
    stamp: u32,
    unsupported: bool,
}

impl<'a> Summaries<'a> {
    /// Adds the summary of the statement at span, f collects what it
    /// references and returns its kind, object, prop and names.
    fn add(
        &mut self,
        span: oxc_span::Span,
        f: impl FnOnce(&mut Collect<'a, '_>) -> (u32, Option<&'a str>, Option<&'a str>, Vec<&'a str>),
    ) {
        self.stamp += 1;
        let mut c = Collect {
            strings: &mut self.strings,
            ids: Vec::new(),
            props: Vec::new(),
            id_seen: std::mem::take(&mut self.id_seen),
            prop_seen: std::mem::take(&mut self.prop_seen),
            stamp: self.stamp,
            unsupported: false,
        };
        let (kind, object, prop, names) = f(&mut c);
        let (ids, props) = (std::mem::take(&mut c.ids), std::mem::take(&mut c.props));
        self.unsupported |= c.unsupported;
        self.id_seen = std::mem::take(&mut c.id_seen);
        self.prop_seen = std::mem::take(&mut c.prop_seen);
        let start = self.utf16.at(span.start);
        let end = self.utf16.at(span.end);
        let object = object.map_or(NONE, |o| self.strings.id(o));
        let prop = prop.map_or(NONE, |p| self.strings.id(p));
        self.out.extend([start, end, kind, object, prop, names.len() as u32]);
        for n in names {
            let i = self.strings.id(n);
            self.out.push(i);
        }
        self.out.push(ids.len() as u32);
        self.out.extend(&ids);
        self.out.push(props.len() as u32);
        self.out.extend(&props);
    }
}

/// The statement summaries of a chunk: a string table, and the statements as
/// u32s: start, end, kind, object, prop (NONE if none), then names, ids and
/// props, each its length and the strings' indexes in the table.
pub fn summarize(source: &str) -> Result<(Vec<String>, Vec<u32>), String> {
    let allocator = Allocator::default();
    summarize_in(&allocator, source)
}

fn summarize_in<'a>(allocator: &'a Allocator, source: &'a str) -> Result<(Vec<String>, Vec<u32>), String> {
    let ret = Parser::new(allocator, source, SourceType::mjs()).parse();
    if let Some(e) = ret.diagnostics.errors().next() {
        return Err(e.to_string());
    }
    let program = allocator.alloc(ret.program);
    let mut s = Summaries {
        strings: Strings { index: FxHashMap::default(), table: Vec::new() },
        out: Vec::with_capacity(program.body.len() * 16),
        utf16: Utf16 { bytes: source.as_bytes(), byte: 0, unit: 0 },
        id_seen: Vec::new(),
        prop_seen: Vec::new(),
        stamp: 0,
        unsupported: false,
    };
    // ESTree's program body starts with the directives
    for d in program.directives.iter() {
        s.add(d.span, |c| {
            c.visit_directive(d);
            (KIND_ROOT, None, None, vec![])
        });
    }
    for stmt in program.body.iter() {
        s.add(stmt.span(), |c| {
            if let Some((object, prop, computed, rhs)) = prop_assignment(stmt).filter(|(_, _, computed, rhs)| {
                is_pure_init(Some(rhs), source) && computed.map_or(true, |k| is_pure_init(Some(k), source))
            }) {
                c.visit_expression(rhs);
                if let Some(k) = computed {
                    c.visit_expression(k);
                }
                c.id(object);
                return (KIND_PROP, Some(object), prop, vec![]);
            }
            if let Some(name) = defonce_decl(stmt, source) {
                walk::walk_statement(c, stmt);
                return (KIND_DECL, None, None, vec![name]);
            }
            let names: Option<Vec<&'a str>> = match stmt {
                Statement::VariableDeclaration(v) => v
                    .declarations
                    .iter()
                    .map(|d| match &d.id {
                        BindingPattern::BindingIdentifier(id) if is_pure_init(d.init.as_ref(), source) => {
                            Some(id.name.as_str())
                        }
                        _ => None,
                    })
                    .collect(),
                Statement::FunctionDeclaration(f) => f.id.as_ref().map(|id| vec![id.name.as_str()]),
                Statement::ClassDeclaration(k) => k.id.as_ref().map(|id| vec![id.name.as_str()]),
                _ => None,
            };
            walk::walk_statement(c, stmt);
            match names {
                Some(names) => (KIND_DECL, None, None, names),
                None => (KIND_ROOT, None, None, vec![]),
            }
        });
    }
    if s.unsupported {
        return Err(UNSUPPORTED.to_string());
    }
    Ok((s.strings.table.iter().map(|s| s.to_string()).collect(), s.out))
}

/// The error of chunks the JavaScript summarizer has to summarize.
pub const UNSUPPORTED: &str = "unsupported";

#[napi(object)]
pub struct ChunkSummary {
    pub strings: Vec<String>,
    pub statements: Uint32Array,
}

/// Summarizes the statements of chunks in parallel, null for the chunks the
/// JavaScript summarizer has to summarize.
#[napi]
pub fn summarize_chunks(codes: Vec<String>) -> napi::Result<Vec<Option<ChunkSummary>>> {
    codes
        .par_iter()
        .map(|code| match summarize(code) {
            Ok((strings, statements)) => Ok(Some((strings, statements))),
            Err(e) if e == UNSUPPORTED => Ok(None),
            Err(e) => Err(e),
        })
        .collect::<Result<Vec<_>, _>>()
        .map(|all| {
            all.into_iter()
                .map(|r| r.map(|(strings, statements)| ChunkSummary { strings, statements: statements.into() }))
                .collect()
        })
        .map_err(|e| napi::Error::from_reason(format!("parsing a chunk for pruning: {e}")))
}
