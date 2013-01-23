package rtkaczyk.eris.service.storage

import rtkaczyk.eris.api.Packet
import rtkaczyk.eris.api.Common

object QueryCache extends Common {
  private val timeout = 60000L
  private var cache = Map[Int, (Long, List[Packet])]()
  
  def apply(queryId: Int): Option[List[Packet]] = {
    cache get queryId match {
      case Some((_, packets)) => Some(packets)
      case None => None
    }
  }
  
  def put(queryId: Int, packets: List[Packet]) {
    val t = now
    cache = (cache filter { t - _._2._1 < timeout }) + (queryId -> (t, packets))
  }
}