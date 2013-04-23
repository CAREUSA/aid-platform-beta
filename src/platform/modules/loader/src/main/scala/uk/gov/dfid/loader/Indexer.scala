package uk.gov.dfid.loader

import com.google.inject.Inject
import reactivemongo.bson._
import reactivemongo.bson.handlers.DefaultBSONHandlers._
import uk.gov.dfid.loader.util.{Sectors, Statuses}
import java.text.NumberFormat
import java.util.Locale
import reactivemongo.bson.BSONLong
import reactivemongo.bson.BSONInteger
import reactivemongo.api.DefaultDB
import reactivemongo.bson.BSONString
import scala.Some
import org.neo4j.cypher.ExecutionEngine
import uk.gov.dfid.common.ElasticSearch
import concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Await
import scala.concurrent.duration.Duration

/**
 * Performs indexing of elastic search data against the aggregated data
 */
class Indexer @Inject()(db: DefaultDB, engine: ExecutionEngine, sectors: Sectors) {

  def index {

    // clear the elastic search
    ElasticSearch.reset

    // perform the various indexing activities
    indexDfidProjects
    indexCountrySuggestions
    indexOtherOrganisationProjects
  }

  private lazy val components = {
    engine.execute(
      """
        | START  component=node:entities(type="iati-activity")
        | MATCH  component-[:`related-activity`]-project,
        |        component-[?:sector]-sector,
        |        component-[?:`recipient-region`]-region,
        |        component-[?:`recipient-country`]-country,
        |        component-[:`participating-org`]-org
        | WHERE  project.type = 1
        | RETURN COLLECT(component.`iati-identifier`) as ids,
        |        project.ref                          as parent,
        |        COLLECT(sector.code)                 as sectors,
        |        COLLECT(region.`recipient-region`)   as regions,
        |        COLLECT(country.`recipient-country`) as countries,
        |        COLLECT(org.`participating-org`?)    as organizations
      """.stripMargin).foldLeft(Map[String, Map[String, List[String]]]()) { (memo, row) =>

      memo + (row("parent").asInstanceOf[String] -> Map(
        "subActivities" -> row("ids").asInstanceOf[List[String]].distinct,
        "countries"     -> row("sectors").asInstanceOf[List[Long]].map(sectors.getHighLevelSector(_)).distinct,
        "regions"       -> row("regions").asInstanceOf[List[String]].distinct,
        "sectors"       -> row("countries").asInstanceOf[List[String]].distinct,
        "organizations" -> row("organizations").asInstanceOf[List[String]].distinct
      ))
    }
  }

  private def indexOtherOrganisationProjects = {
    for(
      projects <- db.collection("other-org-projects").find(BSONDocument()).toList
    ) yield {
      projects.foreach { doc =>
        val id = doc.getAs[BSONString]("iatiId").get.value
        val budget = doc.getAs[BSONLong]("totalBudget").map(_.value).getOrElse(0L)
        val formattedBudget = NumberFormat.getCurrencyInstance(Locale.UK).format(budget)

        // TODO: James Hughes 22 Apr 2012 - need to get a list of all participating orgs
        // TODO: James Hughes 22 Apr 2012 - need to get a list of all countries
        // TODO: James Hughes 22 Apr 2012 - need to get a list of all regions
        // TODO: James Hughes 22 Apr 2012 - need to get a list of all sectors

        val bean = Map(
          "id"              -> id,
          "title"           -> doc.getAs[BSONString]("title").get.value,
          "description"     -> doc.getAs[BSONString]("description").get.value,
          "status"          -> Statuses.get(doc.getAs[BSONLong]("status").get.value).get,
          "budget"          -> budget,
          "formattedBudget" -> formattedBudget.substring(0, formattedBudget.size - 3),
          "organizations"   -> (doc.getAs[BSONString]("orgCode").get.value :: Nil).distinct.mkString("#"),
          "countries"       -> Nil.mkString("#"),
          "regions"         -> Nil.mkString("#"),
          "sectors"         -> Nil.mkString("#")
        )

        ElasticSearch.index(bean, "aid")
      }
    }
  }

  private def indexCountrySuggestions = {
    for (
      countries <- db.collection("countries").find(BSONDocument()).toList;
      stats <- db.collection("country-stats").find(BSONDocument(
        "totalBudget" -> BSONDocument(
          "$gt" -> BSONLong(0L)
        )
      )).toList
    ) yield {
      stats.map {
        stat =>
          val code = stat.getAs[BSONString]("code").get.value
          val name = countries.find(_.getAs[BSONString]("code").get.value == code).head.getAs[BSONString]("name").get.value
          val bean = Map(
            // this is called sugestion - i.e. badly named for some temporary backwards compatibility
            "sugestion"     -> "CountriesSugestion",
            "countryName"   -> name,
            "countryCode"   -> code,
            "countryBudget" -> stat.getAs[BSONLong]("totalBudget").map(_.value).getOrElse(0L)
          )

          ElasticSearch.index(bean, "aid")
      }
    }
  }

  private def indexDfidProjects = {

    // touching components here as it will perform the load into memory.
    // with a full dataset this might be pretty huge adn we should address
    // that ASAP
    components

    val projects = Await.result(db.collection("projects").find(BSONDocument()).toList(), Duration.Inf)

    // loop over projects collection and index the values
    projects.map { doc =>

        try{

          val id = doc.getAs[BSONString]("iatiId").get.value
          val budget = doc.getAs[BSONLong]("totalBudget").map(_.value).getOrElse(0L)
          val formattedBudget = NumberFormat.getCurrencyInstance(Locale.UK).format(budget)
          val component = components(id)

          val organisations = doc.getAs[BSONArray]("participatingOrgs").map {
            values =>
              values.values.toList.flatMap {
                case value =>
                  value match {
                    case v: BSONString => Some(v.value)
                    case _ => None
                  }
              }
          }.getOrElse(List.empty)

          val bean = Map(
            "id"              -> id,
            "title"           -> doc.getAs[BSONString]("title").get.value,
            "description"     -> doc.getAs[BSONString]("description").get.value,
            "status"          -> Statuses.get(doc.getAs[BSONInteger]("status").get.value).get,
            "budget"          -> budget,
            "formattedBudget" -> formattedBudget.substring(0, formattedBudget.size - 3),
            "organizations"   -> (organisations ::: component("organizations")).distinct.mkString("#"),
            "subActivities"   -> component("subActivities").mkString("#"),
            "countries"       -> component("countries").mkString("#"),
            "regions"         -> component("regions").mkString("#"),
            "sectors"         -> component("sectors").mkString("#")
          )

          ElasticSearch.index(bean, "aid")

        }catch {
          case e: Throwable => println(e.getMessage); e.printStackTrace()
        }
    }

  }
}