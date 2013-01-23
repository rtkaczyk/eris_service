package rtkaczyk.eris.service

import android.bluetooth.BluetoothDevice
import rtkaczyk.eris.api.Packet
import rtkaczyk.eris.api.DeviceId
import rtkaczyk.eris.service.bluetooth.DeviceInfo
import scala.actors.Actor

object Msg {
  // common
  case class  Reconfigure(conf: Config)
  case object Kill
  case class  PacketsCount(n: Int)

  // discovery
  case object StartDiscovery
  case object StopDiscovery
  case object DiscoveryStarted
  case object DiscoveryFinished
  case class  DeviceFound(device: BluetoothDevice)
  case class  CacheLoaded(cache: Map[DeviceId, DeviceInfo])
  
  // receiver
  case class  DevicesFound(devices: List[BluetoothDevice])
  case class  ReceiverConnect(device: DeviceId, from: Long = 0, to: Long = 0, limit: Int = 0)
  case class  ReceiverDisconnect(all: Boolean)
  case object ReceiverContinue
  case object AllConnectionsFinished
  
  // storage
  case class  StorePackets(packets: List[Packet])
  case class  FinishStoring(device: DeviceId)
  case class  SelectPackets(queryId: Int, from: Long, to: Long, device: String, limit: Int)
  case class  DeletePackets(from: Long, to: Long, device: String)
  case object LoadCache
  case class  PersistCache(cache: Map[DeviceId, DeviceInfo])
  case object ClearCache
  case object PrepareForwarding
  case class  SelectToForward(n: Int)
  case class  DeleteForwarded(n: Int)
  case class  CountPackets(recv: Actor)
  
  //forwarder
  case class  ForwardPackets(forced: Boolean)
  case class  ForwardingPrepared(n: Int, count: Int)
  case class  PacketsToForward(packets: List[Packet])
  case class  ForwardingFinished(success: Boolean)
  case object CancelForwarding
  case object CancelScheduler
  
}