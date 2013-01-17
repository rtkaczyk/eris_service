package rtkaczyk.eris.service.storage

import scala.actors.Actor
import rtkaczyk.eris.service.ErisService
import rtkaczyk.eris.service.Msg
import android.util.Log
import rtkaczyk.eris.api.Common
import rtkaczyk.eris.api.Packet

class Storage(service: ErisService) extends Actor with Common {
  var store = List[Packet]()
  
  override def act() {
    Log.d(TAG, "Storage started")
    while (true) {
      receive {
        case Msg.StorePackets(packets) => 
          onStorePackets(packets)
          
        case Msg.SelectAllPackets =>
          onSelectAllPackets
          
        case Msg.Kill => 
          onKill
          
        case _ =>
      }
    }
  }
  
  private def onStorePackets(packets: List[Packet]) {
    Log.d(TAG, "Storing %d new packet" format packets.size)
    store = packets ++ store
  }
  
  private def onSelectAllPackets {
    Log.d(TAG, "Replying to query")
    reply(store)
  }
  
  private def onKill {
    Log.d(TAG, "Storage stopped")
    exit
  }
}