package org.tresql

import java.sql.ResultSet
import java.sql.ResultSetMetaData
import sys._

class Result private[tresql] (rs: ResultSet, cols: Vector[Column], env: Env, _columnCount: Int = -1)
  extends Iterator[RowLike] with RowLike with TypedResult {
  private[this] val md = rs.getMetaData
  private[this] val st = rs.getStatement
  private[this] val row = new Array[Any](cols.length)
  private[this] var rsHasNext = true; private[this] var nextCalled = true
  private[this] var closed = false
  private[this] val (colMap, exprCols) = {
    val cwi = cols.zipWithIndex
    (cwi.filter(_._1.name != null).map(t => t._1.name -> t._2).toMap, cwi.filter(_._1.expr != null))
  }
  /** calls jdbc result set next method. after jdbc result set next method returns false closes this result */
  def hasNext = {
    if (rsHasNext && nextCalled) {
      rsHasNext = rs.next; nextCalled = false
      if (rsHasNext) {
        var i = 0
        exprCols foreach { c => row(c._2) = c._1.expr() }
      } else close
    }
    rsHasNext
  }
  def next = { nextCalled = true; this }
  def apply(columnIndex: Int) = {
    if (cols(columnIndex).idx != -1) asAny(cols(columnIndex).idx)
    else row(columnIndex)
  }
  def apply(columnLabel: String) = {
    try {
      apply(colMap(columnLabel))
    } catch { case _: NoSuchElementException => asAny(rs.findColumn(columnLabel)) }
  }
  def typed[T](columnLabel: String)(implicit m: scala.reflect.Manifest[T]): T = {
    typed[T](colMap(columnLabel))
  }
  def columnCount = if (_columnCount == -1) cols.length else _columnCount
  def column(idx: Int) = cols(idx)
  def jdbcResult = rs
  def close {
    if (closed) return
    rs.close
    env.result = null
    if (!env.reusableExpr && st != null) st.close
    closed = true
  }

  //concrete type return methods
  override def int(columnIndex: Int): Int = {
    if (cols(columnIndex).idx != -1) rs.getInt(cols(columnIndex).idx)
    else row(columnIndex).asInstanceOf[Int]
  }
  override def int(columnLabel: String): Int = try int(colMap(columnLabel)) catch {
    case _: NoSuchElementException => int(rs.findColumn(columnLabel))
  }
  override def long(columnIndex: Int): Long = {
    if (cols(columnIndex).idx != -1) rs.getLong(cols(columnIndex).idx)
    else row(columnIndex).asInstanceOf[Long]
  }
  override def long(columnLabel: String): Long = try long(colMap(columnLabel)) catch {
    case _: NoSuchElementException => long(rs.findColumn(columnLabel))
  }
  override def double(columnIndex: Int): Double = {
    if (cols(columnIndex).idx != -1) rs.getDouble(cols(columnIndex).idx)
    else row(columnIndex).asInstanceOf[Double]
  }
  override def double(columnLabel: String): Double = try double(colMap(columnLabel)) catch {
    case _: NoSuchElementException => double(rs.findColumn(columnLabel))
  }
  override def bigdecimal(columnIndex: Int): BigDecimal = {
    if (cols(columnIndex).idx != -1) {
      val bd = rs.getBigDecimal(cols(columnIndex).idx); if (rs.wasNull) null else BigDecimal(bd)
    } else row(columnIndex).asInstanceOf[BigDecimal]
  }
  override def bigdecimal(columnLabel: String): BigDecimal = try bigdecimal(colMap(columnLabel)) catch {
    case _: NoSuchElementException => bigdecimal(rs.findColumn(columnLabel))
  }
  override def string(columnIndex: Int): String = {
    if (cols(columnIndex).idx != -1) rs.getString(cols(columnIndex).idx)
    else row(columnIndex).asInstanceOf[String]
  }
  override def string(columnLabel: String): String = try string(colMap(columnLabel)) catch {
    case _: NoSuchElementException => string(rs.findColumn(columnLabel))
  }
  override def date(columnIndex: Int): java.sql.Date = {
    if (cols(columnIndex).idx != -1) rs.getDate(cols(columnIndex).idx)
    else row(columnIndex).asInstanceOf[java.sql.Date]
  }
  override def date(columnLabel: String): java.sql.Date = try date(colMap(columnLabel)) catch {
    case _: NoSuchElementException => date(rs.findColumn(columnLabel))
  }
  override def timestamp(columnIndex: Int): java.sql.Timestamp = {
    if (cols(columnIndex).idx != -1) rs.getTimestamp(cols(columnIndex).idx)
    else row(columnIndex).asInstanceOf[java.sql.Timestamp]
  }
  override def timestamp(columnLabel: String): java.sql.Timestamp =
    try timestamp(colMap(columnLabel)) catch {
      case _: NoSuchElementException => timestamp(rs.findColumn(columnLabel))
    }
  override def boolean(columnIndex: Int): Boolean = {
    if (cols(columnIndex).idx != -1) rs.getBoolean(cols(columnIndex).idx)
    else row(columnIndex).asInstanceOf[Boolean]
  }
  override def boolean(columnLabel: String): Boolean =
    try boolean(colMap(columnLabel)) catch {
      case _: NoSuchElementException => boolean(rs.findColumn(columnLabel))
    }
  override def bytes(columnIndex: Int): Array[Byte] = {
    if (cols(columnIndex).idx != -1) rs.getBytes(cols(columnIndex).idx)
    else row(columnIndex).asInstanceOf[Array[Byte]]
  }
  override def bytes(columnLabel: String) = try bytes(colMap(columnLabel)) catch {
    case _: NoSuchElementException => bytes(rs.findColumn(columnLabel))
  }
  override def stream(columnIndex: Int) = {
    if (cols(columnIndex).idx != -1) rs.getBinaryStream(cols(columnIndex).idx)
    else row(columnIndex).asInstanceOf[java.io.InputStream]
  }
  override def stream(columnLabel: String) = try stream(colMap(columnLabel)) catch {
    case _: NoSuchElementException => stream(rs.findColumn(columnLabel))
  }

  //java type support
  override def jInt(columnIndex: Int): java.lang.Integer = {
    if (cols(columnIndex).idx != -1) {
      val x = rs.getInt(cols(columnIndex).idx)
      if (rs.wasNull()) null else new java.lang.Integer(x)
    } else row(columnIndex).asInstanceOf[java.lang.Integer]
  }
  override def jInt(columnLabel: String): java.lang.Integer = try jInt(colMap(columnLabel)) catch {
    case _: NoSuchElementException => jInt(rs.findColumn(columnLabel))
  }
  override def jLong(columnIndex: Int): java.lang.Long = {
    if (cols(columnIndex).idx != -1) {
      val x = rs.getLong(cols(columnIndex).idx)
      if (rs.wasNull()) null else new java.lang.Long(x)
    } else row(columnIndex).asInstanceOf[java.lang.Long]
  }
  override def jLong(columnLabel: String): java.lang.Long = try jLong(colMap(columnLabel)) catch {
    case _: NoSuchElementException => jLong(rs.findColumn(columnLabel))
  }
  override def jDouble(columnIndex: Int): java.lang.Double = {
    if (cols(columnIndex).idx != -1) {
      val x = rs.getDouble(cols(columnIndex).idx)
      if (rs.wasNull()) null else new java.lang.Double(x)
    } else row(columnIndex).asInstanceOf[java.lang.Double]
  }
  override def jDouble(columnLabel: String): java.lang.Double = try jDouble(colMap(columnLabel)) catch {
    case _: NoSuchElementException => jDouble(rs.findColumn(columnLabel))
  }
  override def jBigDecimal(columnIndex: Int): java.math.BigDecimal = {
    if (cols(columnIndex).idx != -1) rs.getBigDecimal(cols(columnIndex).idx)
    else row(columnIndex).asInstanceOf[java.math.BigDecimal]
  }
  override def jBigDecimal(columnLabel: String): java.math.BigDecimal =
    try jBigDecimal(colMap(columnLabel)) catch {
      case _: NoSuchElementException => jBigDecimal(rs.findColumn(columnLabel))
    }
  override def jBoolean(columnIndex: Int): java.lang.Boolean = {
    if (cols(columnIndex).idx != -1) {
      val x = rs.getBoolean(cols(columnIndex).idx)
      if (rs.wasNull()) null else new java.lang.Boolean(x)
    } else row(columnIndex).asInstanceOf[java.lang.Boolean]
  }
  override def jBoolean(columnLabel: String): java.lang.Boolean =
    try jBoolean(colMap(columnLabel)) catch {
      case _: NoSuchElementException => jBoolean(rs.findColumn(columnLabel))
    }

  override def toList = this.map(r => Row(this.content)).toList

  def toListOfMaps: List[Map[String, _]] = this.map(r => rowAsMap).toList

  def rowAsMap = (0 to (columnCount - 1)).map(i => column(i).name -> (this(i) match {
    case r: Result => r.toListOfMaps
    case x => x
  })).toMap

  def content = {
    val b = new scala.collection.mutable.ListBuffer[Any]
    var i = 0
    while (i < columnCount) {
      b += (this(i) match {
        case r: Result => r.toList
        case x => x
      })
      i += 1
    }
    Vector(b: _*)
  }

  /**
   * iterates through this result rows as well as these of descendant result
   * ensures execution of dml (update, insert, delete) expressions in colums otherwise
   * has no side effect.
   */
  def execute: Unit = foreach { r =>
    {
      var i = 0
      while (i < columnCount) {
        this(i) match {
          case r: Result => r.execute
          case _ =>
        }
        i += 1
      }
    }
  }

  /** needs to be overriden since super class implementation calls hasNext method */
  override def toString = getClass.toString + ":" + (cols.mkString(","))
  private def asAny(pos: Int): Any = {
    import java.sql.Types._
    md.getColumnType(pos) match {
      case ARRAY | BINARY | BLOB | DATALINK | DISTINCT | JAVA_OBJECT | LONGVARBINARY | NULL |
        OTHER | REF | STRUCT | VARBINARY => rs.getObject(pos)
      //scala BigDecimal is returned instead of java.math.BigDecimal
      //because it can be easily compared using standart operators (==, <, >, etc) 
      case DECIMAL | NUMERIC => {
        val bd = rs.getBigDecimal(pos); if (rs.wasNull) null else BigDecimal(bd)
      }
      case BIGINT | INTEGER | SMALLINT | TINYINT => {
        val v = rs.getLong(pos); if (rs.wasNull) null else v
      }
      case BIT | BOOLEAN =>
        val v = rs.getBoolean(pos); if (rs.wasNull) null else v
      case VARCHAR | CHAR | CLOB | LONGVARCHAR => rs.getString(pos)
      case DATE => rs.getDate(pos)
      case TIME | TIMESTAMP => rs.getTimestamp(pos)
      case DOUBLE | FLOAT | REAL => val v = rs.getDouble(pos); if (rs.wasNull) null else v
    }
  }

  case class Row(row: Seq[Any]) extends RowLike {
    def apply(idx: Int) = row(idx)
    def apply(name: String) = error("unsupported method")
    def typed[T](name: String)(implicit m: scala.reflect.Manifest[T]) = error("unsupported method")
    def content = row
    def columnCount = row.length
    def column(idx: Int) = Result.this.column(idx)
    override def equals(row: Any) = row match {
      case r: RowLike => this.row == r.content
      case r: Seq[_] => this.row == r
      case _ => false
    }
  }

}

trait RowLike extends Dynamic with Typed {
  /* Dynamic objects */
  /**
   * Wrapper for dynamical result column access as Int
   * This uses scala.Dynamic feature.
   * Example usages:
   * {{{
   * val result: RowLike = ...
   * println(result.i.salary)
   * }}}
   *
   */
  private[tresql] object DynamicInt extends Dynamic {
    def selectDynamic(col: String) = int(col)
    def applyDynamic(col: String)(args: Any*) = selectDynamic(col)
  }
  private[tresql] object DynamicJInt extends Dynamic {
    def selectDynamic(col: String) = jInt(col)
    def applyDynamic(col: String)(args: Any*) = selectDynamic(col)
  }
  /**
   * Wrapper for dynamical result column access as Long
   * This uses scala.Dynamic feature.
   * Example usages:
   * {{{
   * val result: RowLike = ...
   * println(result.l.salary)
   * }}}
   *
   */
  private[tresql] object DynamicLong extends Dynamic {
    def selectDynamic(col: String) = long(col)
    def applyDynamic(col: String)(args: Any*) = selectDynamic(col)
  }
  private[tresql] object DynamicJLong extends Dynamic {
    def selectDynamic(col: String) = jLong(col)
    def applyDynamic(col: String)(args: Any*) = selectDynamic(col)
  }
  /**
   * Wrapper for dynamical result column access as Double
   * This uses scala.Dynamic feature.
   * Example usages:
   * {{{
   * val result: RowLike = ...
   * println(result.dbl.salary)
   * }}}
   *
   */
  private[tresql] object DynamicDouble extends Dynamic {
    def selectDynamic(col: String) = double(col)
    def applyDynamic(col: String)(args: Any*) = selectDynamic(col)
  }
  private[tresql] object DynamicJDouble extends Dynamic {
    def selectDynamic(col: String) = jDouble(col)
    def applyDynamic(col: String)(args: Any*) = selectDynamic(col)
  }
  /**
   * Wrapper for dynamical result column access as BigDecimal
   * This uses scala.Dynamic feature.
   * Example usages:
   * {{{
   * val result: RowLike = ...
   * println(result.bd.salary)
   * }}}
   *
   */
  private[tresql] object DynamicBigDecimal extends Dynamic {
    def selectDynamic(col: String) = bigdecimal(col)
    def applyDynamic(col: String)(args: Any*) = selectDynamic(col)
  }
  private[tresql] object DynamicJBigDecimal extends Dynamic {
    def selectDynamic(col: String) = jBigDecimal(col)
    def applyDynamic(col: String)(args: Any*) = selectDynamic(col)
  }
  /**
   * Wrapper for dynamical result column access as String
   * This uses scala.Dynamic feature.
   * Example usages:
   * {{{
   * val result: RowLike = ...
   * println(result.s.name)
   * }}}
   *
   */
  private[tresql] object DynamicString extends Dynamic {
    def selectDynamic(col: String) = string(col)
    def applyDynamic(col: String)(args: Any*) = selectDynamic(col)
  }
  /**
   * Wrapper for dynamical result column access as java.sql.Date
   * This uses scala.Dynamic feature.
   * Example usages:
   * {{{
   * val result: RowLike = ...
   * println(result.d.birthdate)
   * }}}
   *
   */
  private[tresql] object DynamicDate extends Dynamic {
    def selectDynamic(col: String) = date(col)
    def applyDynamic(col: String)(args: Any*) = selectDynamic(col)
  }
  /**
   * Wrapper for dynamical result column access as java.sql.Timestamp
   * This uses scala.Dynamic feature.
   * Example usages:
   * {{{
   * val result: RowLike = ...
   * println(result.t.eventTime)
   * }}}
   *
   */
  private[tresql] object DynamicTimestamp extends Dynamic {
    def selectDynamic(col: String) = timestamp(col)
    def applyDynamic(col: String)(args: Any*) = selectDynamic(col)
  }
  /**
   * Wrapper for dynamical result column access as Boolean
   * This uses scala.Dynamic feature.
   * Example usages:
   * {{{
   * val result: RowLike = ...
   * println(result.bl.is_active)
   * }}}
   *
   */
  private[tresql] object DynamicBoolean extends Dynamic {
    def selectDynamic(col: String) = boolean(col)
    def applyDynamic(col: String)(args: Any*) = selectDynamic(col)
  }
	
  private[tresql] object DynamicJBoolean extends Dynamic {
    def selectDynamic(col: String) = jBoolean(col)
    def applyDynamic(col: String)(args: Any*) = selectDynamic(col)
  }
  /**
   * Wrapper for dynamical result column access as org.tresql.Result
   * This uses scala.Dynamic feature.
   * Example usages:
   * {{{
   * val result: RowLike = ...
   * println(result.r.childResult)
   * }}}
   *
   */
  private[tresql] object DynamicResult extends Dynamic {
    def selectDynamic(col: String) = result(col)
    def applyDynamic(col: String)(args: Any*) = selectDynamic(col)
  }
  //wrappers for array and stream
  private[tresql] object DynamicByteArray extends Dynamic {
    def selectDynamic(col: String) = bytes(col)
    def applyDynamic(col: String)(args: Any*) = selectDynamic(col)
  }
  private[tresql] object DynamicStream extends Dynamic {
    def selectDynamic(col: String) = stream(col)
    def applyDynamic(col: String)(args: Any*) = selectDynamic(col)
  }
  
  def apply(idx: Int): Any
  def apply(name: String): Any
  def selectDynamic(name: String) = apply(name)
  def applyDynamic(name: String)(args: Any*) = selectDynamic(name)
  def int(idx: Int) = typed[Int](idx)
  def int(name: String) = typed[Int](name)
  def int = DynamicInt
  def i = int
  def i(idx: Int) = int(idx)
  def i(name: String) = int(name)
  def long(idx: Int) = typed[Long](idx)
  def long(name: String) = typed[Long](name)
  def long = DynamicLong
  def l = long
  def l(idx: Int) = long(idx)
  def l(name: String) = long(name)
  def double(idx: Int) = typed[Double](idx)
  def double(name: String) = typed[Double](name)
  def double = DynamicDouble
  def dbl = double
  def dbl(idx: Int) = double(idx)
  def dbl(name: String) = double(name)
  def bigdecimal(idx: Int) = typed[BigDecimal](idx)
  def bigdecimal(name: String) = typed[BigDecimal](name)
  def bigdecimal = DynamicBigDecimal
  def bd = bigdecimal
  def bd(idx: Int) = bigdecimal(idx)
  def bd(name: String) = bigdecimal(name)
  def string(idx: Int) = typed[String](idx)
  def string(name: String) = typed[String](name)
  def string = DynamicString
  def s = string
  def s(idx: Int) = string(idx)
  def s(name: String) = string(name)
  def date(idx: Int) = typed[java.sql.Date](idx)
  def date(name: String) = typed[java.sql.Date](name)
  def date = DynamicDate
  def d = date
  def d(idx: Int) = date(idx)
  def d(name: String) = date(name)
  def timestamp(idx: Int) = typed[java.sql.Timestamp](idx)
  def timestamp(name: String) = typed[java.sql.Timestamp](name)
  def timestamp = DynamicTimestamp
  def t = timestamp
  def t(idx: Int) = timestamp(idx)
  def t(name: String) = timestamp(name)
  def boolean(idx: Int) = typed[Boolean](idx)
  def boolean(name: String) = typed[Boolean](name)
  def boolean = DynamicBoolean
  def bl = boolean
  def bl(idx: Int) = boolean(idx)
  def bl(name: String) = boolean(name)
  def bytes(idx: Int) = typed[Array[Byte]](idx)
  def bytes(name: String) = typed[Array[Byte]](name)
  def bytes = DynamicByteArray
  def b = bytes
  def b(idx: Int) = bytes(idx)
  def b(name: String) = bytes(name)
  def stream(idx: Int) = typed[java.io.InputStream](idx)
  def stream(name: String) = typed[java.io.InputStream](name)
  def stream = DynamicStream
  def bs = stream
  def bs(idx: Int) = stream(idx)
  def bs(name: String) = stream(name)
  def result(idx: Int) = typed[Result](idx)
  def result(name: String) = typed[Result](name)
  def result = DynamicResult
  def r = result
  def r(idx: Int) = result(idx)
  def r(name: String) = result(name)
  def jInt(idx: Int) = typed[java.lang.Integer](idx)
  def jInt(name: String) = typed[java.lang.Integer](name)
  def jInt = DynamicJInt
  def ji = jInt
  def ji(idx: Int) = jInt(idx)
  def ji(name: String) = jInt(name)
  def jLong(idx: Int) = typed[java.lang.Long](idx)
  def jLong(name: String) = typed[java.lang.Long](name)
  def jLong = DynamicJLong
  def jl = jLong
  def jl(idx: Int) = jLong(idx)
  def jl(name: String) = jLong(name)
  def jBoolean(idx: Int) = typed[java.lang.Boolean](idx)
  def jBoolean(name: String) = typed[java.lang.Boolean](name)
  def jBoolean = DynamicJBoolean
  def jbl = jBoolean
  def jbl(idx: Int) = jBoolean(idx)
  def jbl(name: String) = jBoolean(name)
  def jDouble(idx: Int) = typed[java.lang.Double](idx)
  def jDouble(name: String) = typed[java.lang.Double](name)
  def jDouble = DynamicJDouble
  def jdbl = jDouble
  def jdbl(idx: Int) = jDouble(idx)
  def jdbl(name: String) = jDouble(name)
  def jBigDecimal(idx: Int) = typed[java.math.BigDecimal](idx)
  def jBigDecimal(name: String) = typed[java.math.BigDecimal](name)
  def jBigDecimal = DynamicJBigDecimal
  def jbd = jBigDecimal
  def jbd(idx: Int) = jBigDecimal(idx)
  def jbd(name: String) = jBigDecimal(name)
  def columnCount: Int
  def content: Seq[Any]
  def column(idx: Int): Column
}

case class Column(val idx: Int, val name: String, private[tresql] val expr: Expr)