package rtkaczyk.eris.service

import rtkaczyk.eris.api.IErisApi
import java.util.{ ArrayList => JArrayList }
import scala.collection.JavaConversions._
import rtkaczyk.eris.api.Common
import rtkaczyk.eris.api.Packet
import android.util.Log

class API(val Service: ErisService) extends IErisApi.Stub() with Common {
  lazy val Storage = Service.storage
  lazy val DiscoveryAgent = Service.discoveryAgent
  lazy val Receiver = Service.receiver
  
  override def getAllPackets: JArrayList[Packet] = {
    Log.d(TAG, "API: getLastPackets")
    //val packets = (Storage !? Msg.SelectAllPackets).asInstanceOf[List[Packet]]
    val packets = Storage.store
    Log.d(TAG, "Fetched %d packets from storage" format packets.size)
    new JArrayList(packets)
  }
  
  override def xmlConfigure(xml: String) {
    Log.d(TAG, "API: xmlConfigure")
    Config.xml = xml
    Service.reconfigure
  }
  
  override def getXmlConfig() = {
    Log.d(TAG, "API: getXmlConfig")
    Config.xml
  }
}