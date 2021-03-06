package org.tresql

trait Transformer { self: QueryBuilder =>

  def transform(expr: Expr, f: PartialFunction[Expr, Expr]): Expr = {
    var cf: PartialFunction[Expr, Expr] = null
    cf = f.orElse[Expr, Expr] {
      case null => null
      case e if e.builder != self => e.builder.transform(e, f)
    }.orElse[Expr, Expr] {
      case ArrExpr(e) => ArrExpr(e map cf)
      case AssignExpr(v, e) => AssignExpr(v, cf(e))
      case BinExpr(o, lop, rop) => BinExpr(o, cf(lop), cf(rop))
      case BracesExpr(b) => BracesExpr(cf(b))
      case ColExpr(col, alias, typ, sepQuery, hidden) => ColExpr(cf(col), alias, typ, sepQuery, hidden)
      case ExternalFunExpr(n, p, m) => ExternalFunExpr(n, p map cf, m)
      case FunExpr(n, p, d) => FunExpr(n, p map cf, d)
      case Group(e, h) => Group(e map cf, cf(h))
      case HiddenColRefExpr(e, typ) => HiddenColRefExpr(cf(e), typ)
      case InExpr(lop, rop, not) => InExpr(cf(lop), rop map cf, not)
      case i: InsertExpr => new InsertExpr(cf(i.table).asInstanceOf[IdentExpr], i.alias,
        i.cols map cf, cf(i.vals))
      case Order(exprs) => Order(exprs map (e => (cf(e._1), cf(e._2), cf(e._3))))
      case SelectExpr(tables, filter, cols, distinct, group, order,
        offset, limit, aliases, parentJoin) =>
        SelectExpr(tables map (t => cf(t).asInstanceOf[Table]), cf(filter),
          cols map (e => cf(e).asInstanceOf[ColExpr]), distinct, cf(group), cf(order),
          cf(offset), cf(limit), aliases, parentJoin map cf)
      case Table(texpr, alias, join, outerJoin, nullable) =>
        Table(cf(texpr), alias, cf(join).asInstanceOf[TableJoin], outerJoin, nullable)
      case TableJoin(default, expr, noJoin, defaultJoinCols) =>
        TableJoin(default, cf(expr), noJoin, defaultJoinCols)
      case UnExpr(o, op) => UnExpr(o, cf(op))
      case u: UpdateExpr => new UpdateExpr(cf(u.table).asInstanceOf[IdentExpr], u.alias,
        u.filter map cf, u.cols map cf, cf(u.vals))
      case d: DeleteExpr => //put delete at the end since it is superclass of insert and update
        DeleteExpr(cf(d.table).asInstanceOf[IdentExpr], d.alias, d.filter map cf)
      case ValuesExpr(vals) => ValuesExpr(vals map cf)
      case e => e
    }
    cf(expr)
  }
}