package uk.gov.dfid.common

import org.elasticsearch.node.NodeBuilder
import org.elasticsearch.common.settings.ImmutableSettings
import scala.util.Properties
import org.elasticsearch.index.query.{QueryStringQueryBuilder, QueryBuilders}
import scala.collection.JavaConversions._
import org.elasticsearch.action.index.IndexRequest

object ElasticSearch {

  lazy private val path     = Properties.envOrElse("DFID_ELASTICSEARCH_PATH", "/dfid/elastic" )
  lazy private val settings = ImmutableSettings.settingsBuilder.put("path.data", path)
  lazy private val node     = NodeBuilder.nodeBuilder.local(true).data(true).settings(settings).node

  node.client.admin.cluster.prepareHealth().setWaitForYellowStatus.execute.actionGet

  def search(search: String) = {

    val client = node.client()
    val query = QueryBuilders.queryString(sanitize(search)).defaultOperator(QueryStringQueryBuilder.Operator.AND)
    val response = client.prepareSearch().setQuery(query).setSize(999).execute.actionGet
    response.getHits.getHits.map(_.getSource.toMap).toList
  }

  def reset = {
    node.client.admin().indices().prepareDelete().execute().actionGet()
  }

  def index(data: Map[String, Any], name: String) = {
    val request = new IndexRequest(name, "index")
    request.source(data)
    node.client.index(request).actionGet()
  }

  def sanitize(query: String) = {
    // replace characters that can break the search e.g. \+-&|!(){}[]^~*?:/"
    query.replaceAll("[\\(\\)\\*\\?\\^\\[\\]}{!|&~:/+\\\\\"]", "")
  }
}
