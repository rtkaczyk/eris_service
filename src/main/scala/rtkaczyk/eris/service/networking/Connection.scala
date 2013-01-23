package rtkaczyk.eris.service.networking

import rtkaczyk.eris.service.bluetooth.Receiver
import android.bluetooth.BluetoothDevice
import rtkaczyk.eris.service.bluetooth.BtConnection
import rtkaczyk.eris.api.Common
import rtkaczyk.eris.service.SafeActor
import rtkaczyk.eris.service.forwarding.Forwarder
import rtkaczyk.eris.service.forwarding.FwdConnection


trait Connection extends SafeActor {
  def inProgress = false
  def cancel {}
  override def start: Connection = {
    super.start
    this
  }
}

object Connection {
  private object FakeConnection extends Connection { def act {} }
  
  def apply(): Connection = FakeConnection
  def apply(receiver: Receiver, device: BluetoothDevice, req: BtConnection.Request): Connection =
    new BtConnection(receiver, device, req)
  def apply(forwarder: Forwarder): Connection =
    new FwdConnection(forwarder)
}