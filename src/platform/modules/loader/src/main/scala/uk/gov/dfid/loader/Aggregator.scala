package uk.gov.dfid.loader

import org.joda.time.DateTime
import concurrent.ExecutionContext.Implicits.global
import reactivemongo.bson._
import reactivemongo.bson.handlers.DefaultBSONHandlers._
import org.neo4j.cypher.ExecutionEngine
import org.neo4j.graphdb.Node
import uk.gov.dfid.common.api.Api
import uk.gov.dfid.common.models.Project
import concurrent.duration._
import uk.gov.dfid.common.DataLoadAuditor
import reactivemongo.bson.BSONString
import reactivemongo.bson.BSONLong
import uk.gov.dfid.loader.Implicits._
import reactivemongo.bson.BSONInteger
import reactivemongo.api.DefaultDB
import reactivemongo.api.indexes.{IndexType, Index}
import concurrent._
import java.util.Date
import java.text.SimpleDateFormat

/**
 * Aggregates a bunch of data related to certain elements
 */
class Aggregator(engine: ExecutionEngine, db: DefaultDB, projects: Api[Project], auditor: DataLoadAuditor) {

  def loadProjects = {

    auditor.info("Loading Projects")
    auditor.info("Dropping current projects collection")

    val formatter = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");

    // drop the collection and start up
    Await.ready(db.collection("projects").drop, Duration.Inf)

    // create an index.  this will prevent iati ids being duped.
    Await.ready(db.collection("projects").indexesManager.create(
      Index("iatiId" -> IndexType.Ascending :: Nil, unique = true)
    ), Duration.Inf)

    auditor.success("Current projects collection dropped")

    try {

      auditor.info("Getting all H1 DFID Projects")

      engine.execute(
        """
          |START  n=node:entities(type="iati-activity")
          |MATCH  p-[:`participating-org`]-n-[:`reporting-org`]-o,
          |       n-[?:`iati-identifier`]-id,
          |       n-[:`activity-status`]-a
          |WHERE  n.hierarchy! = 1
          |AND    o.ref = "GB-1"
          |RETURN n, COALESCE(n.`iati-identifier`?, id.`iati-identifier`?) as id,
          |       a.code as status,
          |       COLLECT(p) as participating,
          |       coalesce(n.`last-updated-datetime`?, "1991-08-02T00:00:00") as lastUpdatedDateTime
        """.stripMargin).foreach { row =>

        val projectNode           = row("n").asInstanceOf[Node]
        val status                = row("status").asInstanceOf[Long].toInt
        val title                 = projectNode.getProperty("title").toString
        val description           = projectNode.getPropertySafe[String]("description").getOrElse("")
        val id                    = row("id").asInstanceOf[String]
        val lastUpdatedDateTime   = formatter.parse(row("lastUpdatedDateTime").asInstanceOf[String])
        val projectOrgs           = row("participating").asInstanceOf[List[Node]]
        //val projectOrgs = row("participating").asInstanceOf[List[String]].filterNot(_ == "UNITED KINGDOM")



        val projectType = id match {
          case i if (globalProjects.exists(_._1.equals(i)))   => "global"
          case i if (countryProjects.exists(_._1.equals(i)))  => "country"
          case i if (regionalProjects.exists(_._1.equals(i))) => "regional"
          case _ => "undefined"
        }

        val reportingOrg = "Department for International Development"

        val recipient = projectType match {
          case "country"  => countryProjects.find(_._1 == id).map(_._2)
          case "regional" => regionalProjects.find(_._1 == id).map(_._2)
          case "global"   => globalProjects.find(_._1 == id).map(_._2)
          case _          => None
        }

        // we need to collect child activity orgs as well.
        val componentOrgs = engine.execute(
          s"""
            |START  v=node:entities(type="iati-activity")
            |MATCH  p-[:`participating-org`]-v-[:`related-activity`]-a
            |WHERE  a.ref = '$id'
            |AND    a.type=1
            |RETURN p as org
          """.stripMargin).map { s =>
          s("org").asInstanceOf[Node]
        }

        val orgs = (projectOrgs ++ componentOrgs).toList

        val participatingOrgs = orgs.flatMap { case org =>
          org.getPropertySafe[String]("participating-org")
        }

        val implementingOrgs = orgs.flatMap { case org =>
          org.getPropertySafe[String]("role") match  {
            case Some("Implementing") => org.getPropertySafe[String]("participating-org")
            case _                    => None
          }
        }

        val allRecipients = engine.execute(
           s""" START n=node:entities(type="iati-activity")
                MATCH rr-[?:`recipient-region`]-n-[:`related-activity`]-r,
                 rc-[?:`recipient-country`]-n
                WHERE r.ref = '$id'
                AND r.type = 1
                RETURN DISTINCT(COALESCE(rc.`recipient-country`, rr.`recipient-region`, n.`recipient-region`!, "")) as recipient
               """.stripMargin).map{row =>

          val recipient = row("recipient").asInstanceOf[String]

          if(recipient.contains("(") && recipient.trim.endsWith(")")) {
            recipient.substring(0,recipient.indexOf("(")).trim
          } else {
            recipient
          }}.toList

        val project = Project(None, id, title, description, projectType, reportingOrg, lastUpdatedDateTime,
          recipient,allRecipients, status, None, participatingOrgs.distinct.sorted.filterNot(_ == "UNITED KINGDOM"), implementingOrgs.distinct.sorted)

        Await.ready(projects.insert(project), Duration.Inf)
      }

      auditor.success("All projects loaded")
    }catch{
      case e: Throwable => e.printStackTrace(); auditor.error(s"Error loading projects: ${e.getMessage}")
    }
  }

  def rollupCountrySectorBreakdown = {

    auditor.info("Dropping sector breakdowns collection")
    // drop the collection and start up
    Await.ready(db.collection("sector-breakdowns").drop, Duration.Inf)
    auditor.info("Sector breakdowns collection dropped")

    auditor.info("Rolling up country sector breakdown")

    val sectorBreakdowns = db.collection("sector-breakdowns")

    engine.execute(
      s"""
        | START n=node:entities(type="iati-activity")
        | MATCH n-[:`recipient-country`]-c,
        |       n-[:`reporting-org`]-o,
        |       n-[:sector]-s
        | WHERE n.hierarchy! = 2
        | AND   o.ref = "GB-1"
        | RETURN distinct c.code as country, s.code as sector, s.sector as name, COUNT(s) as total
        | ORDER BY total DESC
       """.stripMargin).toSeq.foreach { row =>
        try {
          val country = row("country").asInstanceOf[String]
          val sector = row("sector").asInstanceOf[Long].toString
          val name = row("name").asInstanceOf[String]
          val total = row("total").asInstanceOf[Long].toInt

          sectorBreakdowns.insert(
            BSONDocument(
              "country" -> BSONString(country),
              "sector"  -> BSONString(sector),
              "name"    -> BSONString(name),
              "total"   -> BSONInteger(total)
            )
          )
        } catch {
          case e: Throwable => println(e.getMessage); println(e.getStackTraceString)
        }
      }
      auditor.success("Country sectors rolled up")
  }

  def rollupCountryProjectBudgets = {
    auditor.info("Dropping project budgets collection")
    // drop the collection and start up
    Await.ready(db.collection("project-budgets").drop, Duration.Inf)

    auditor.info("Project budgets dropped")
    auditor.info("Rolling up country project budgets")

    val projectBudgets = db.collection("project-budgets")

    engine.execute(
      s"""
      |  START  b=node:entities(type="budget")
      |  MATCH  v-[:value]-b-[:budget]-component-[:`related-activity`]-proj,
      |         component-[:`reporting-org`]-org,
      |         b-[:`period-start`]-period
      |  WHERE  proj.type = 1
      |  AND    org.ref   = "GB-1"
      |  RETURN proj.ref          as projectId,
      |         v.value           as value,
      |         period.`iso-date` as date
       """.stripMargin).toSeq.foreach { row =>
      try {
        val id = row("projectId").asInstanceOf[String]
        val value = row("value").asInstanceOf[Long].toInt
        val date = row("date").asInstanceOf[String]

        projectBudgets.insert(
          BSONDocument(
             "id" -> BSONString(id),
             "value" -> BSONInteger(value),
             "date" -> BSONString(date)
          )
        )
      } catch {
        case e: Throwable => println(e.getMessage); println(e.getStackTraceString)
      }
    }
    auditor.success("Project budgets rolled up for countries")
  }

  def rollupProjectBudgets = {
    auditor.info("Rolling up Project Budgets")

    val projects = db.collection("projects")
    val (start, end) = currentFinancialYear

    // clear all values from the projects
    Await.ready(projects.update(
      BSONDocument(),
      BSONDocument("$set" -> BSONDocument(
        "totalBudget" -> BSONLong(0),
        "currentFYBudget" -> BSONLong(0),
        "totalProjectSpend" -> BSONLong(0)
      )
    ), multi = true), Duration Inf)

    auditor.info("Summing up all budgets for all projects")
    engine.execute(
      s"""
        | START  n=node:entities(type="iati-activity")
        | MATCH  n-[:`related-activity`]-a,
        |        n-[:budget]-b-[:value]-v,
        |        b-[:`period-start`]-p
        | WHERE  a.type = 1
        | AND    n.hierarchy! = 2
        | RETURN a.ref as id, v.value as value, p.`iso-date` as date
      """.stripMargin).foreach { row =>
      val id = row("id").asInstanceOf[String]
      val budget = row("value") match {
        case v: java.lang.Integer => v.toLong
        case v: java.lang.Long    => v.toLong
      }
      val date = row("date").asInstanceOf[String]
      val currentFy = date >= start && date <= end

      projects.update(
        BSONDocument("iatiId" -> BSONString(id)),
        BSONDocument(
          "$inc" -> BSONDocument("totalBudget" -> BSONLong(budget)),
          "$inc" -> BSONDocument("currentFYBudget" -> BSONLong(if(currentFy) budget else 0L)
        )),
        upsert = false, multi = false
      )
    }

    engine.execute(
      s"""
        | START  txn = node:entities(type="transaction")
        | MATCH  project-[:`related-activity`]-component-[:transaction]-txn,
        |        component-[:`reporting-org`]-org,
        |        txn-[:value]-value,
        |        txn-[:`transaction-date`]-date,
        |        txn-[:`transaction-type`]-type
        | WHERE  project.type = 1
        | AND    org.ref      = "GB-1"
        | AND    (type.`code` = 'D' OR type.`code` = 'E')
        | RETURN
        | distinct project.ref as id,
        | sum(value.value)     as spend
      """.stripMargin).foreach { row =>
      val id = row("id").asInstanceOf[String]
      val spend = row("spend") match {
        case v: java.lang.Integer => v.toLong
        case v: java.lang.Long    => v.toLong
      }

      projects.update(
        BSONDocument("iatiId" -> BSONString(id)),
        BSONDocument("$set" -> BSONDocument(
          "totalProjectSpend" -> BSONLong(spend)
        )),
        upsert = false, multi = false
      )
    }

    auditor.success("Project budgets rolled up")
  }

  def rollupCountryBudgets = {

    auditor.info("Rolling up Country Budgets")

    val (start, end) = currentFinancialYear

    auditor.info("Fetching current countries from CMS")

    val countries = Await.result(db.collection("countries").find(BSONDocument()).toList, Duration.Inf)

    auditor.info("Summing all budgets for project (from current FY)")

    countries.foreach { countryDocument =>

      val country = countryDocument.toTraversable
      val code = country.getAs[BSONString]("code").get.value

      try {
        val query = s"""
          | START  n=node:entities(type="iati-activity")
          | MATCH  n-[:`recipient-country`]-c,
          |        n-[:`reporting-org`]-org,
          |        n-[:budget]-b-[:value]-v,
          |        b-[:`period-start`]-p
          | WHERE  org.ref="GB-1"
          | AND    c.code = "$code"
          | AND    p.`iso-date` >= "$start"
          | AND    p.`iso-date` <= "$end"
          | RETURN SUM(v.value) as value
        """.stripMargin

        val result = engine.execute(query).columnAs[Object]("value")

        val totalBudget = result.toSeq.head match {
          case v: java.lang.Integer => v.toLong
          case v: java.lang.Long    => v.toLong
        }

        // update the country stats collection
        db.collection("country-stats").update(
          BSONDocument("code" -> BSONString(code)),
          BSONDocument("$set" -> BSONDocument(
            "code"        -> BSONString(code),
            "totalBudget" -> BSONLong(totalBudget)
          )),
          multi = false,
          upsert = true
        )
      }
      catch {
        case e: Throwable => {
          auditor.error(s"Error rolling up budgets for country $code : ${e.getMessage}")
        }
      }
    }
  }

  private def currentFinancialYear = {
    val now = DateTime.now
    if (now.getMonthOfYear < 4) {
      s"${now.getYear-1}-04-01" -> s"${now.getYear}-03-31"
    } else {
      s"${now.getYear}-04-01" -> s"${now.getYear + 1}-03-31"
    }
  }

  private lazy val countryProjects = {
    engine.execute(
      """
        | START  n=node:entities(type="iati-activity")
        | MATCH  n-[:`recipient-country`]-a,
        |        n-[:`related-activity`]-p,
        |        n-[:`reporting-org`]-o
        | WHERE  n.hierarchy! = 2
        | AND    p.type=1
        | AND    o.ref = "GB-1"
        | RETURN DISTINCT(p.ref) as id, a.code as recipient
      """.stripMargin).toSeq.map { row =>
      row("id").asInstanceOf[String] -> row("recipient").asInstanceOf[String]
    }
  }

  private lazy val regionalProjects = {
    engine.execute(
      """
        | START n=node:entities(type="iati-activity")
        | MATCH n-[r?:`recipient-region`]-a,
        |       n-[:`related-activity`]-p
        | WHERE n.hierarchy! = 2
        | // Parent Activity must have a
        | AND   p.type=1
        | AND   (
        |       (r is not null)
        |   OR  (
        |         (r is null)
        |     AND (
        |          n.`recipient-region`! = "Balkan Regional (BL)"
        |       OR n.`recipient-region`! = "East Africa (EA)"
        |       OR n.`recipient-region`! = "Indian Ocean Asia Regional (IB)"
        |       OR n.`recipient-region`! = "Latin America Regional (LE)"
        |       OR n.`recipient-region`! = "East African Community (EB)"
        |       OR n.`recipient-region`! = "EECAD Regional (EF)"
        |       OR n.`recipient-region`! = "East Europe Regional (ED)"
        |       OR n.`recipient-region`! = "Francophone Africa (FA)"
        |       OR n.`recipient-region`! = "Central Africa Regional (CP)"
        |       OR n.`recipient-region`! = "Overseas Territories (OT)"
        |       OR n.`recipient-region`! = "South East Asia (SQ)"
        |     )
        |   )
        | )
        | RETURN DISTINCT(p.ref) as id, a.code as code, n.`recipient-region`? as region
      """.stripMargin).toSeq.map { row =>
      val id   = row("id").asInstanceOf[String]
      val code = row("code") match {
        case null => "\\((\\w{2})\\)$".r.findFirstMatchIn(row("region").asInstanceOf[String]).get.group(1)
        case code => code.toString
      }

      id -> code
    }
  }

  private lazy val  globalProjects = {
    engine.execute(
      """
        | START n=node:entities(type="iati-activity")
        | MATCH n-[:`related-activity`]-p
        | WHERE n.hierarchy! = 2
        | AND  (n.`recipient-region`! = "Administrative/Capital (AC)"
        |    OR n.`recipient-region`! = "Non Specific Country (NS)"
        |    OR n.`recipient-region`! = "Multilateral Organisation (ZZ)")
        | AND   p.type=1
        | RETURN DISTINCT(p.ref) as id, n.`recipient-region`! as region
      """.stripMargin).toSeq.map { row =>
      val id     = row("id").asInstanceOf[String]
      val region =  "\\((\\w{2})\\)$".r.findFirstMatchIn(row("region").asInstanceOf[String]).get.group(1)

      id -> region
    }
  }

  def collectFundingTraceability = {

    auditor.info("Collecting multilevel traceability")
    Await.ready(db.collection("multilevel-traceablity").drop(), Duration.Inf)

    try { 
      
  engine.execute(
      s"""
        | START  n=node:entities(type="iati-activity")
        | MATCH n-[:transaction]-t-[:`transaction-type`]-tt,
        |       n-[:transaction]-t-[:`provider-org`]-po
        | WHERE tt.code = "IF"
        | RETURN po.`provider-activity-id` as Funding, collect(distinct (n.`iati-identifier`)) as Funded
      """.stripMargin).foreach { row =>

      val funding     = row("Funding").asInstanceOf[String]
      val funded       = row("Funded").asInstanceOf[Seq[String]]

      println(s"$funding, $funded")

      db.collection("multilevel-traceablity").insert(
        BSONDocument(
          "funding"     -> BSONString(funding),
          "funded" -> BSONArray(
            funded.map(c => BSONString(c)): _*
          )
        )
      )
    }

    auditor.success("Collected multilevel traceability")

    } catch {
      case e: Throwable => println(e.getMessage); e.printStackTrace()
    }
  }
}
