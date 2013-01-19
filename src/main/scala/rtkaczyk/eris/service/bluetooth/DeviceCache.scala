package rtkaczyk.eris.service.bluetooth

import rtkaczyk.eris.api.Common
import rtkaczyk.eris.api.DeviceId
import android.bluetooth.BluetoothDevice
import android.util.Log


case class DeviceInfo (
  device: BluetoothDevice,
  to: Long,
  tau: Long
)


object DeviceCache extends Common {
  private var period = 0L
  private var cache = Map[DeviceId, DeviceInfo]()
  
  def ignorePeriod_= (p: Int) { period = p.toLong * 1000 }
  def ignorePeriod = (period / 1000).toInt
  
  def isReady(device: DeviceId) =
    cache get device match {
      case Some(info) => now - info.tau > period
      case None => true
    }
  
  def update(device: DeviceId, to: Long) {
    //Log.w(TAG, "Updating device [%s], to: %d" format (device, to))
    cache get device match {
      case Some(DeviceInfo(dev, t, _)) => 
        cache += device -> DeviceInfo(dev, if (to > t) to else t, now)
        
      case None =>
    }
  }
  
  def add(device: BluetoothDevice) {
    val id = DeviceId(device)
    cache += id -> (cache get id match {
      case Some(DeviceInfo(_, to, tau)) =>
        DeviceInfo(device, to, tau)
        
      case None =>
        DeviceInfo(device, 0, 0)
    })
  }
  
  def getFrom(device: DeviceId) = {
    cache get device match {
      case Some(DeviceInfo(_, to, _)) => to
      case None => 0L
    }
  }
  
  def getDevice(device: DeviceId): Option[BluetoothDevice] = {
    cache get device match {
      case Some(DeviceInfo(dev, _, _)) if dev != null => Some(dev)
      case _ => None
    }
  }
}