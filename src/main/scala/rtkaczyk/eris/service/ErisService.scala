package rtkaczyk.eris.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import rtkaczyk.eris.service.bluetooth._
import rtkaczyk.eris.service.storage.Storage
import scala.actors.Actor
import rtkaczyk.eris.api.Common
import rtkaczyk.eris.api.Packet
import rtkaczyk.eris.api.IErisApi
import rtkaczyk.eris.api.Events._
import rtkaczyk.eris.service.bluetooth.DiscoveryAgent

class ErisService extends Service with Common {

  var _discoveryAgent: DiscoveryAgent = new DiscoveryAgent(this)
  var _receiver: Receiver = new Receiver(this)
  var _storage: Storage = new Storage(this)
  
  def discoveryAgent = _discoveryAgent
  def receiver = _receiver
  def storage = _storage

  val api = new API(this)
  
  private var started = false

  override def onBind(intent: Intent) = {
    Log.d(TAG, "Binding service: [%s]" format intent.getAction)
    api
  }

  override def onCreate {
    super.onCreate
    Log.d(TAG, "onCreate")
  }
  
  override def onStartCommand(intent: Intent, flags: Int, startId: Int) = {
    Log.d(TAG, "onStartCommand")
    if (!started) {
      Config.xml = intent getStringExtra "xmlConfig"
      init
      this !! ErisStarted
    }
    started = true
    Service.START_STICKY
  }

  override def onDestroy {
    Log.d(TAG, "onDestroy")
    this !! ErisStopped

    discoveryAgent ! Msg.Kill
    receiver ! Msg.Kill
    storage ! Msg.Kill

    super.onDestroy
  }

  def !! (event: ErisEvent) {
    sendBroadcast(event.toIntent, ErisPermission)
  }
  
  def reconfigure {
    Log.d(TAG, "Reconfiguring service")
    discoveryAgent ! Msg.Reconfigure(Config.getSub("discovery"))
    receiver ! Msg.Reconfigure(Config.getSub("receiver"))
  }  
  
  private def configure {
    Log.d(TAG, "Configuring service")
    discoveryAgent configure Config.getSub("discovery")
    receiver configure Config.getSub("receiver")
  }
  
  private def init {
    _discoveryAgent = new DiscoveryAgent(this)
    _receiver = new Receiver(this)
    _storage = new Storage(this)
    
    configure
    
    discoveryAgent.start
    receiver.start
    storage.start
  }
}