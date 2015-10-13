package org.tresql

import sys._

/** Object Relational Transformations - ORT */
trait ORT extends Query {

  case class TableLink(table: String, refs: Set[String])
  case class Property(
    tables: List[TableLink],
    insert: Boolean,
    update: Boolean,
    delete: Boolean,
    alias: String)

  /* table[:ref]*[#table[:ref]*]*[[options]] [alias]
  *  Examples:
  *    dept#car:deptnr:nr#tyres:carnr:nr
  *    dept[+=] alias
  */
  val PROP_PATTERN = {
    val ident = """[^:\[\]\s#]+"""
    val table = s"$ident(?::$ident)*"
    val tables = s"""($table(?:#$table)*)"""
    val options = """(?:\[(\+?-?=?)\])?"""
    val alias = """(?:\s+(\w+))?"""
    (tables + options + alias)r
  }

  type ObjToMapConverter[T] = (T) => (String, Map[String, _])

  /** QueryBuilder methods **/
  override private[tresql] def newInstance(e: Env, depth: Int, idx: Int) =
    new ORT {
      override def env = e
      override private[tresql] def queryDepth = depth
      override private[tresql] var bindIdx = idx
    }

  /* Expression is built only from macros to ensure ORT lookup editing. */
  case class LookupEditExpr(
    obj: String,
    idName: String,
    insertExpr: Expr,
    updateExpr: Expr)
  extends BaseExpr {
    override def apply() = env(obj) match {
      case m: Map[String, Any] =>
        if (idName != null && (m contains idName) && m(idName) != null) {
          val lookupObjId = m(idName)
          updateExpr(m)
          lookupObjId
        } else extractId(insertExpr(m))
      case null => null
      case x => error(s"Cannot set environment variables for the expression. $x is not a map.")
    }
    def extractId(result: Any) = result match {
      case (_, id) => id //insert expression
      case s: Seq[_] => s.last match { case (_, id) => id } //array expression
      case x => error(s"Unable to extract id from expr result: $x, expr: $insertExpr")
    }
    def defaultSQL = s"LookupEditExpr($obj, $idName, $insertExpr, $updateExpr)"
  }
  /* Expression is built from macros to ensure ORT children editing */
  case class InsertOrUpdateExpr(table: String, insertExpr: Expr, updateExpr: Expr)
  extends BaseExpr {
    val idName = env.table(table).key.cols.headOption.orNull
    override def apply() =
      if (idName != null && env.containsNearest(idName) && env(idName) != null)
        updateExpr() else insertExpr()
    def defaultSQL = s"InsertOrUpdateExpr($idName, $insertExpr, $updateExpr)"
  }
  /* Expression is built from macros to ensure ORT children editing */
  case class DeleteChildrenExpr(obj: String, table: String, expr: Expr)
  extends BaseExpr {
    val idName = env.table(table).key.cols.headOption.orNull
    override def apply() = {
      env(obj) match {
        case s: Seq[Map[String, _]] =>
          expr(if (idName != null)
            Map("ids" -> s.map(_(idName)).filter(_ != null)) else Map[String, Any]())
      }
    }
    override def defaultSQL = s"DeleteChildrenExpr($obj, $idName, $expr)"
  }
  /* Expression is built from macro.
   * Effectively env.currId(idSeq, IdRefExpr(idRefSeq)())*/
  case class IdRefIdExpr(idRefSeq: String, idSeq: String) extends BaseVarExpr {
    val idRefExpr = IdRefExpr(idRefSeq)
    override def apply() = {
      val id = idRefExpr()
      env.currId(idSeq, id)
      id
    }
    override def toString = s"$idRefExpr#$idSeq"
  }

  def insert(name: String, obj: Map[String, Any], filter: String = null)
    (implicit resources: Resources = Env): Any = {
    val struct = tresql_structure(obj)
    val insert = insert_tresql(name, struct, Nil, filter)
    if(insert == null) error("Cannot insert data. Table not found for object: " + name)
    Env log (s"\nStructure: $name - $struct")
    build(insert, obj, false)(resources)()
  }

  def update(name: String, obj: Map[String, Any], filter: String = null)
    (implicit resources: Resources = Env): Any = {
    val struct = tresql_structure(obj)
    val update = update_tresql(name, struct, Nil, filter)
    if(update == null) error(s"Cannot update data. Table not found or no primary key or no updateable columns found for the object: $name")
    Env log (s"\nStructure: $name - $struct")
    build(update, obj, false)(resources)()
  }

  def delete(name: String, id: Any, filter: String = null, filterParams: Map[String, Any] = null)
  (implicit resources: Resources = Env): Any = {
    val Array(tableName, alias) = name.split("\\s+").padTo(2, null)
    (for {
      table <- resources.metaData.tableOption(tableName)
      pk <- table.key.cols.headOption
      if table.key.cols.size == 1
    } yield {
      val delete = s"-${table.name}${Option(alias).map(" " + _).getOrElse("")}[$pk = ?${Option(filter)
        .map(f => s" & ($f)").getOrElse("")}]"
      build(delete, Map("1" -> id) ++ Option(filterParams).getOrElse(Map()), false)(resources)()
    }) getOrElse {
      error(s"Table $name not found or table primary key not found or table primary key consists of more than one column")
    }
  }

  /** insert methods to multiple tables
   *  Tables must be ordered in parent -> child direction. */
  def insertMultiple(obj: Map[String, Any], names: String*)(filter: String = null)
    (implicit resources: Resources = Env): Any = insert(multiSaveProp(names), obj, filter)

  /** update to multiple tables
   *  Tables must be ordered in parent -> child direction. */
  def updateMultiple(obj: Map[String, Any], names: String*)(filter: String = null)
    (implicit resources: Resources = Env): Any = update(multiSaveProp(names), obj, filter)

  def multiSaveProp(names: Seq[String])(implicit resources: Resources = Env) =
    names.tail.foldLeft(List(names.head)) { (ts, t) =>
      (t.split(":").head + importedKeysAndPks(t, ts, resources)
        .mkString(":", ":", "")) :: ts
  }.reverse.mkString("#")

  //object methods
  def insertObj[T](obj: T, filter: String = null)(
      implicit resources: Resources = Env, conv: ObjToMapConverter[T]): Any = {
    val v = conv(obj)
    insert(v._1, v._2, filter)
  }
  def updateObj[T](obj: T, filter: String = null)(
      implicit resources: Resources = Env, conv: ObjToMapConverter[T]): Any = {
    val v = conv(obj)
    update(v._1, v._2, filter)
  }

  def tresql_structure[M <: Map[String, Any]](obj: M)(
    /* ensure that returned map is of the same type as passed.
     * For example in the case of ListMap when key ordering is important. */
    implicit bf: scala.collection.generic.CanBuildFrom[M, (String, Any), M]): M = {
    def merge(lm: Seq[Map[String, Any]]): Map[String, Any] =
      lm.tail.foldLeft(tresql_structure(lm.head))((l, m) => {
        val x = tresql_structure(m)
        l map (t => (t._1, (t._2, x.getOrElse(t._1, null)))) map {
          case (k, (v1: Map[String, _], v2: Map[String, _])) if v1.size > 0 && v2.size > 0 =>
            (k, merge(List(v1, v2)))
          case (k, (v1: Map[String, _], _)) if v1.size > 0 => (k, v1)
          case (k, (_, v2: Map[String, _])) if v2.size > 0 => (k, v2)
          case (k, (v1, _)) => (k, v1)
        }
      })
    obj.map {
      case (k, Seq() | Array()) => (k, Map())
      case (k, l: Seq[Map[String, _]]) => (k, merge(l))
      case (k, l: Array[Map[String, _]]) => (k, merge(l))
      case (k, m: Map[String, Any]) => (k, tresql_structure(m))
      case x => x
    }(bf.asInstanceOf[scala.collection.generic.CanBuildFrom[Map[String, Any], (String, Any), M]]) //somehow cast is needed
  }

  def insert_tresql(
    name: String,
    struct: Map[String, Any],
    parents: List[TableLink],
    filter: String)(implicit resources: Resources): String = {
    def insert(
      table: metadata.Table,
      alias: String,
      refsAndPk: Set[(String, String)],
      children: List[String],
      filter: String,
      notLink: Boolean): String =
      struct.flatMap { case (n, v) => v match {
        //children or lookup
        case o: Map[String, Any] => lookupObject(n, table).map(lookupTable =>
          lookup_tresql(n, lookupTable, o, resources)).getOrElse {
          List(insert_tresql(n, o, TableLink(table.name,
            refsAndPk.map(_._1)) :: parents, null/*do not pass filter*/) -> null)
        }
        //pk or ref to parent
        case _ if refsAndPk.exists(_._1 == n) => Nil
        //ordinary field
        case _ => List(table.colOption(n).map(_.name).orNull -> resources.valueExpr(table.name, n))
      }
    }.groupBy { case _: String => "l" case _ => "b"} match {
      case m: Map[String, List[_]] =>
      val tableName = table.name
      //lookup edit tresql
      val lookupTresql = m.get("l").map(_.asInstanceOf[List[String]].map(_ + ", ").mkString).orNull
      //base table tresql
      val tresql =
        (m.getOrElse("b", Nil).asInstanceOf[List[(String, String)]]
         .filter(_._1 != null /*check if prop->col mapping found*/) ++
           refsAndPk ++ children.map(_ -> null)/*add same level one to one children*/)
         match {
           case x if x.size == 0 => null //no insertable columns found
           case cols_vals =>
             val (cols, vals) = cols_vals.unzip
             cols.mkString(s"+$tableName {", ", ", "}") +
             (vals.filter(_ != null) match {
               case vs if filter == null => vs.mkString(" [", ", ", "]")
               case vs =>
                 val toa = if (alias == null) tableName else alias
                 val cv = cols_vals filter (_._2 != null)
                 val sel = s"($tableName{${cv.map(c =>
                   c._2 + " " + c._1).mkString(", ")}} @(1)) $toa"
                 cv.map(c => s"$toa.${c._1}").mkString(s" $sel [$filter] {", ", ", "}")
             })
         }
       val tresqlAlias = parents.headOption.filter(_ => notLink)
         .map(_ => s" '$name'").getOrElse("")
       Option(tresql).map(t => Option(lookupTresql).map(lt => s"[$lt$t]$tresqlAlias")
         .getOrElse(t + tresqlAlias)).orNull
    }
    val Property(tables, _, _, _, alias) = parseProperty(name)
    val parent = parents.headOption.map(_.table).orNull
    val md = resources.metaData
    (for {
      propTable <- tables.headOption
      table <- md.tableOption(propTable.table)
      refs <- Some(parent).filter(_ == null).map(_ => Set[String]()) /*no parent no refs*/ orElse
        Some(propTable.refs)
         .filter(rfs => rfs.size > 0 && isRefInSet(rfs, table, parent)) /* refs in prop */orElse
          importedKeyOption(table, parent).map(Set(_))
    } yield {
      val pk = table.key.cols match {case List(k) => k case _ => null /*no key or multi col key*/}
      def idRefId(idRef: String, id: String) = s"_id_ref_id($idRef, $id)"
      val linkedTresqls = for{ linkedTable <- tables.tail
        tableDef <- md.tableOption(linkedTable.table) } yield insert(
          tableDef, alias, linkedTable.refs.map(_ -> idRefId(
            table.name, tableDef.name)),
          Nil, null, false) //no children & do not pass filter & and reset not link flag to linked tresqls
      insert(
        table,
        alias,
        refs.map(r=> r -> (if (r == pk) idRefId(parent, table.name) //pk matches ref to parent
          else s":#$parent")) ++ (if (pk == null || refs.contains(pk)) Set()
          else Set(pk -> s"#${table.name}")),
        linkedTresqls.filter(_ != null), filter, true)
    }).orNull
  }

  def update_tresql(
    name: String,
    struct: Map[String, Any],
    parents: List[TableLink],
    filter: String)(implicit resources: Resources): String = {
    def update(
      table: metadata.Table,
      alias: String,
      refsAndPk: Set[(String, String)],
      children: List[String],
      filter: String,
      notLink: Boolean): String = struct.flatMap {
      case (n, v) => v match {
        //children
        case o: Map[String, _] => lookupObject(n, table).map(lookupTable =>
          lookup_tresql(n, lookupTable, o, resources)).getOrElse {
          List(update_tresql(n, o, TableLink(table.name,
            refsAndPk.map(_._1)) :: parents, null/*do not pass filter*/) -> null)
        }
        //pk or ref to parent
        case _ if refsAndPk exists (_._1 == n) => Nil
        //ordinary field
        case _ => List(table.colOption(n).map(_.name).orNull -> resources.valueExpr(table.name, n))
      }
    }.groupBy { case _: String => "l" case _ => "b" } match {
      case m: Map[String, List[_]] =>
        (m("b").asInstanceOf[List[(String, String)]].filter(_._1 != null) ++
          children.map(_ -> null)/*add same level one to one children*/).unzip match {
          case (cols: List[String], vals: List[String]) =>
            val lookupTresql = m.get("l").map(_.asInstanceOf[List[String]].map(_ + ", ").mkString).orNull
            val tn = table.name + (if (alias == null) "" else " " + alias)
            val updateFilter = refsAndPk.map(t=> s"${t._1} = ${t._2}")
              .mkString("[", " & ", s"${if (filter != null) s" & ($filter)" else ""}]")
            val tresql =
              cols.mkString(s"=$tn $updateFilter {", ", ", "}") +
              vals.filter(_ != null).mkString("[", ", ", "]")
            //TODO do not add alias if this is linked tresql!
            val tresqlAlias = parents.headOption.filter(_ => notLink)
              .map(_ => s" '$name'").getOrElse("")
            if (cols.size == 0) null else Option(lookupTresql)
              .map(lt => s"[$lt$tresql]$tresqlAlias")
              .getOrElse(tresql + tresqlAlias)
      }
    }
    val Property(tables, insertOption, updateOption, deleteOption, alias) =
      parseProperty(name)
    val parent = parents.headOption.map(_.table).orNull
    val md = resources.metaData
    (for {
      propTable <- tables.headOption
      table <- md.tableOption(propTable.table)
      refs <- Some(parent).filter(_ == null).map(_ => Set[String]()) /*no parent no refs*/ orElse
        Some(propTable.refs)
          .filter(rfs => rfs.size > 0 && isRefInSet(rfs, table, parent)) /* refs in prop */orElse
          importedKeyOption(table, parent).map(Set(_))
    } yield {
      val pk = table.key.cols match {case List(k) => k case _ => null /*no key or multi col key*/}
      val isOneToOne = refs contains pk
      val tableName = table.name
      def idRefId(idRef: String, id: String) = s"_id_ref_id($idRef, $id)"
      def stripTrailingAlias(tresql: String, alias: String) =
        if (tresql != null && tresql.endsWith(alias))
          tresql.dropRight(alias.length) else tresql
      def delAllChildren = s"-$tableName[${refs.map(_ + s" = :#$parent").mkString(" & ")}]"
      def delMissingChildren =
        s"""_delete_children('$name', '$tableName', -${table
          .name}[${refs.map(_ + s" = :#$parent").mkString(" & ")} & $pk !in :ids])"""
      def ins = insert_tresql(name, struct, parents, null /*do not pass filter*/)
      def insOrUpd = s"""|_insert_or_update('$tableName', ${
        stripTrailingAlias(ins, s" '$name'")}, ${
        stripTrailingAlias(upd, s" '$name'")}) '$name'"""
      def upd: String = {
        val linkedTresqls = for{ linkedTable <- tables.tail
          tableDef <- md.tableOption(linkedTable.table) } yield update(
            tableDef, alias, linkedTable.refs.map(_ -> idRefId(
              tableName, tableDef.name)),
            Nil, null, false) //no children & do not pass filter & and reset not link flag to linked tresqls
        update(table, alias, refs.map(r=> r -> (
          if (r == pk) idRefId(parent, tableName) //pk matches ref to parent
          else s":#$parent")) ++ (if (isOneToOne) Set() else Set(pk -> s"#$tableName")),
          linkedTresqls.filter(_ != null), filter, true)
      }
      if (parent == null) if (pk == null) null else upd
      else if (isOneToOne) upd else
        if (pk == null) {
          (Option(deleteOption).filter(_ == true).map(_ => delAllChildren) ++
          Option(insertOption).filter(_ == true)
            .flatMap(_ => Option(ins))).mkString(", ")
        } else {
          (Option(deleteOption).filter(_ == true).map(_ =>
            if(!updateOption) delAllChildren else delMissingChildren) ++
          ((insertOption, updateOption) match {
            case (true, true) => Option(insOrUpd)
            case (true, false) => Option(ins)
            case (false, true) => Option(upd)
            case (false, false) => None
          })).mkString(", ")
        }
    }).orNull
  }

  def lookup_tresql(
    refColName: String,
    name: String,
    struct: Map[String, _],
    resources: Resources) =
    resources.metaData.tableOption(name).filter(_.key.cols.size == 1).map {
      table =>
      val pk = table.key.cols.headOption.filter(struct contains).orNull
      val insert = insert_tresql(name, struct, Nil, null)(resources)
      val update = update_tresql(name, struct, Nil, null)(resources)
      List(
        s":$refColName = |_lookup_edit('$refColName', ${
          if (pk == null) "null" else s"'$pk'"}, $insert, $update)",
        refColName -> resources.valueExpr(name, refColName))
    }.orNull

  def lookupObject(refColName: String, table: metadata.Table) =
    table.refTable.get(List(refColName))

  private def parseProperty(name: String) = {
    val PROP_PATTERN(tables, options, alias) = name
    //insert update delete option
    val (i, u, d) = Option(options).map (_ =>
      (options contains "+", options contains "=", options contains "-")
    ).getOrElse {(true, false, true)}
    Property((tables split "#").map{ t =>
      val x = t split ":"
      TableLink(x.head, x.tail.toSet)
    }.toList, i, u, d, alias)
  }

  private def importedKeyOption(childTable: metadata.Table, parent: String) =
    Option(childTable.refs(parent).filter(_.cols.size == 1)).flatMap {
      case Nil => None
      case List(ref) => ref.cols.headOption
      case x => error(
        s"""Ambiguous references from table '${childTable.name}' to table '$parent'.
           |Reference must be one and must consist of one column. Found: $x"""
           .stripMargin)
    }

  /* Returns zero or one imported key from table for each relation. In the case of multiple
   * imported keys pointing to the same relation the one specified after : symbol is chosen
   * or exception is thrown.
   * This is used to find relation columns for insert/update multiple methods.
   * Additionaly primary key of table is returned if it consist only of one column */
  def importedKeysAndPks(tableName: String, relations: List[String], resources: Resources) = {
    val x = tableName split ":"
    val table = resources.metaData.table(x.head)
    relations.foldLeft(x.tail.toSet) { (keys, rel) =>
      val relation = rel.split(":").head
      val refs = table.refs(relation).filter(_.cols.size == 1)
      (if (refs.size == 1) keys + refs.head.cols.head
      else if (refs.size == 0 || refs.exists(r => keys.contains(r.cols.head))) keys
      else error(s"Ambiguous refs: $refs from table ${table.name} to table $relation")) ++
      (table.key.cols match {case List(k) => Set(k) case _ => Set()})
    }
  }

  def isRefInSet(refs: Set[String], child: metadata.Table, parent: String) =
    child.refs(parent).filter(_.cols.size == 1).exists(r => refs.contains(r.cols.head))
}

object ORT extends ORT
