package rtkaczyk.eris.service.storage

import rtkaczyk.eris.service.ErisService
import rtkaczyk.eris.service.Msg
import android.util.Log
import rtkaczyk.eris.api.Common
import rtkaczyk.eris.api.Packet
import rtkaczyk.eris.service.SafeActor
import scala.actors.Actor.actor
import rtkaczyk.eris.service.Config
import rtkaczyk.eris.service.bluetooth.DeviceInfo
import rtkaczyk.eris.api.DeviceId
import rtkaczyk.eris.api.Events._
import scala.actors.Actor

class Storage(val service: ErisService) extends SafeActor {
  val dbAdapter = new DbAdapter(service)
  
  lazy val discovery = service.discovery
  lazy val forwarder = service.forwarder
  private var idStream = Stream from 0
  
  private class Inserter extends SafeActor {
    var n = 0
    var started = false
    def act {
      started = true
      loop {
        react {
          case Msg.StorePackets(packets) =>
            n += dbAdapter insertPackets packets
            
          case Msg.FinishStoring(device) => {
            service !! PacketsStored(device, n)
            exit
          } 
        }
      }
    }
  }
  private var inserter = new Inserter
  
  private object Cfg {
    var capacity = 10 * 1024 * 1024
    var vacPercent = 0.2
    var vacEachBatch = false
  }
  
  def capacity = Cfg.capacity
  def vacPercent = Cfg.vacPercent
  def vacEachBatch = Cfg.vacEachBatch
  
  override def act {
    Log.d(TAG, "Storage started")
    loop {
      react {
        case Msg.Reconfigure(conf) =>
          onReconfigure(conf)
        
        case m @ Msg.StorePackets(_) => 
          onStorePackets(m)
          
        case m @ Msg.FinishStoring(_) =>
          onFinishStoring(m)
          
        case Msg.SelectPackets(id, from, to, device, limit) =>
          onSelectPackets(id, from, to, device, limit)
          
        case Msg.DeletePackets(from, to, device) =>
          onDeletePackets(from, to, device)
          
        case Msg.LoadCache =>
          onLoadCache
        
        case Msg.PersistCache(cache) =>
          onPersistCache(cache)
          
        case Msg.ClearCache =>
          onClearCache
          
        case Msg.PrepareForwarding =>
          onPrepareForwarding
          
        case Msg.SelectToForward(n) =>
          onSelectToForward(n)
          
        case Msg.DeleteForwarded(n) =>
          onDeleteForwarded(n)
          
        case Msg.CountPackets(recv) =>
          onCountPackets(recv)
          
        case Msg.Kill => 
          onKill
      }
    }
  }
  
  def nextQueryId = {
    idStream = idStream.tail
    idStream.head
  }
  
  def configure(conf: Config) {
    Cfg.capacity = conf.get("capacity", Config.positiveInt, 10 * 1024) * 1024
    Cfg.vacPercent = conf.get("vacuum-percent", Config.positivePercent, 20.0) / 100.0
    Cfg.vacEachBatch = conf.getBool("vacuum-after-each-batch", false)
  }

  private def onReconfigure(conf: Config) {
    Log.d(TAG, "onReconfigure")
    configure(conf)
  }
  
  private def onStorePackets(m: Msg.StorePackets) {
    Log.d(TAG, "onStorePackets")
    if (!inserter.started)
      inserter.start
    
    inserter ! m
  }
  
  private def onFinishStoring(m: Msg.FinishStoring) {
    Log.d(TAG, "onFinishStoring")
    inserter ! m
    inserter = new Inserter
  }
  
  private def onSelectPackets(queryId: Int, from: Long, to: Long, device: String, limit: Int) {
    Log.d(TAG, "onSelectPackets")
    actor {
      val packets = dbAdapter.selectPackets(from, to, device, limit)
      QueryCache put (queryId, packets)
      service !! QueryCompleted(queryId)
    }
  }
  
  private def onDeletePackets(from: Long, to: Long, device: String) {
    Log.d(TAG, "onDeletePackets")
    actor {
      dbAdapter.deletePackets(from, to, device)
    }
  }
  
  private def onLoadCache {
    Log.d(TAG, "onLoadCache")
    actor {
      discovery ! Msg.CacheLoaded(dbAdapter.loadCache)
    }
  }
  
  private def onPersistCache(cache: Map[DeviceId, DeviceInfo]) {
    Log.d(TAG, "onPersistCache")
    actor {
      dbAdapter persistCache cache
    }
  }
  
  private def onClearCache {
    Log.d(TAG, "onClearCache")
    actor {
      dbAdapter.clearCache
    }
  }
  
  private def onPrepareForwarding {
    Log.d(TAG, "onPrepareForwarding")
    actor {
      val (n, count) = dbAdapter prepareForwarding forwarder.batchSize
      forwarder ! Msg.ForwardingPrepared(n, count)
    }
  }
  
  private def onSelectToForward(n: Int) {
    Log.d(TAG, "onSelectForwarded")
    actor {
      val packets = dbAdapter selectToForward n
      forwarder ! Msg.PacketsToForward(packets)
    }
  }
  
  private def onDeleteForwarded(n: Int) {
    Log.d(TAG, "onDeleteForwarded")
    actor {
      dbAdapter deleteForwarded n
    }
  }
  
  private def onCountPackets(recv: Actor) {
    Log.d(TAG, "onCountPackets")
    actor {
      recv ! Msg.PacketsCount(dbAdapter.packetsCount)
    }
  }
  
  
  private def onKill {
    Log.d(TAG, "Storage stopped")
    dbAdapter.close
    exit
  }
}