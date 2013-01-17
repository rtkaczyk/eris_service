package rtkaczyk.eris.service

import android.bluetooth.BluetoothDevice
import rtkaczyk.eris.api.Packet
import rtkaczyk.eris.api.DeviceId

object Msg {
  case class  Reconfigure(conf: Config)
  
  case object StartDiscovery
  case object StopDiscovery
  case object DiscoveryStarted
  case object DiscoveryFinished
  case class  DeviceFound(device: BluetoothDevice)
  case class  DevicesFound(devices: List[BluetoothDevice])
  
  case object AllConnectionsFinished
  case class  ReceiverConnect(device: DeviceId, from: Long = 0, to: Long = 0, limit: Int = 0)
  case object ReceiverDisconnect
  case object ReceiverContinue
  
  case class  StorePackets(packets: List[Packet])
  case class  SelectPacketsSince(time: Long)
  case object SelectAllPackets
  
  case object Kill
}