package io.iohk.ethereum.nodebuilder

import java.security.SecureRandom

import akka.actor.{ActorRef, ActorSystem}
import akka.agent.Agent
import io.iohk.ethereum.blockchain.data.GenesisDataLoader
import io.iohk.ethereum.blockchain.sync.{BlockchainHostActor, SyncController}
import io.iohk.ethereum.db.components.Storages.PruningModeComponent
import io.iohk.ethereum.db.components.{DataSourcesComponent, SharedLevelDBDataSources, Storages, StoragesComponent}
import io.iohk.ethereum.db.storage.AppStateStorage
import io.iohk.ethereum.db.storage.pruning.PruningMode
import io.iohk.ethereum.domain._
import io.iohk.ethereum.governance.CertificateAuthorityManagerImpl
import io.iohk.ethereum.jsonrpc.server.JsonRpcServer.JsonRpcServerConfig
import io.iohk.ethereum.jsonrpc.NetService.NetServiceConfig
import io.iohk.ethereum.ledger.{Ledger, LedgerImpl}
import io.iohk.ethereum.network.{PeerManagerActor, ServerActor}
import io.iohk.ethereum.jsonrpc._
import io.iohk.ethereum.jsonrpc.server.JsonRpcServer
import io.iohk.ethereum.keystore.{KeyStore, KeyStoreImpl}
import io.iohk.ethereum.mining.{BlockGenerator, ProofOfStakeMiner}
import io.iohk.ethereum.network.PeerManagerActor.PeerConfiguration
import io.iohk.ethereum.network.EtcPeerManagerActor.PeerInfo
import io.iohk.ethereum.utils._

import scala.concurrent.ExecutionContext.Implicits.global
import io.iohk.ethereum.network._
import io.iohk.ethereum.network.discovery.{DiscoveryConfig, DiscoveryListener, PeerDiscoveryManager}
import io.iohk.ethereum.network.handshaker.{EtcHandshaker, EtcHandshakerConfiguration, Handshaker}
import io.iohk.ethereum.network.p2p.EthereumMessageDecoder
import io.iohk.ethereum.network.rlpx.AuthHandshaker
import io.iohk.ethereum.transactions.PendingTransactionsManager
import io.iohk.ethereum.validators._
import io.iohk.ethereum.vm.VM
import io.iohk.ethereum.ommers.OmmersPool
import io.iohk.ethereum.pos.ElectionManagerImpl
import io.iohk.ethereum.timing.{BeaconActor, Clock, NTPClock}
import io.iohk.ethereum.utils.Config.SyncConfig

import scala.concurrent.duration._

// scalastyle:off number.of.types
trait BlockchainConfigBuilder {
  lazy val blockchainConfig = BlockchainConfig(Config.config)
}

trait SyncConfigBuilder {
  lazy val syncConfig = SyncConfig(Config.config)
}

trait TxPoolConfigBuilder {
  lazy val txPoolConfig = TxPoolConfig(Config.config)
}

trait MiningConfigBuilder {
  lazy val miningConfig = MiningConfig(Config.config)
}

trait FilterConfigBuilder {
  lazy val filterConfig = FilterConfig(Config.config)
}

trait NodeKeyBuilder {
  self: SecureRandomBuilder =>
  lazy val nodeKey = loadAsymmetricCipherKeyPair(Config.nodeKeyFile, secureRandom)
}

trait ActorSystemBuilder {
  implicit lazy val actorSystem = ActorSystem("mantis_system")
}

trait PruningConfigBuilder extends PruningModeComponent {
  lazy val pruningMode: PruningMode = PruningConfig(Config.config).mode
}

trait StorageBuilder {
  lazy val storagesInstance: StoragesComponent with DataSourcesComponent =
    new SharedLevelDBDataSources with PruningConfigBuilder with Storages.DefaultStorages
}

trait DiscoveryConfigBuilder {
  lazy val discoveryConfig = DiscoveryConfig(Config.config)
}

trait OuroborosConfigBuilder {
  lazy val ouroborosConfig = OuroborosConfig(Config.config)
}

trait CertificateAuthorityManagerBuilder {
  self: LedgerBuilder =>
  lazy val certificateAuthorityManager = CertificateAuthorityManagerImpl(ledger.simulateTransaction)
}

trait ElectionManagerBuilder {
  self: OuroborosConfigBuilder
    with CertificateAuthorityManagerBuilder =>

  lazy val electionManager = ElectionManagerImpl(certificateAuthorityManager, ouroborosConfig)
}

trait KnownNodesManagerBuilder {
  self: ActorSystemBuilder
    with StorageBuilder =>

  lazy val config = KnownNodesManager.KnownNodesManagerConfig(Config.config)

  lazy val knownNodesManager = actorSystem.actorOf(KnownNodesManager.props(config, storagesInstance.storages.knownNodesStorage), "known-nodes-manager")
}

trait PeerDiscoveryManagerBuilder {
  self: ActorSystemBuilder
  with DiscoveryListenerBuilder
  with NodeStatusBuilder
  with DiscoveryConfigBuilder
  with StorageBuilder =>

  lazy val peerDiscoveryManager =
    actorSystem.actorOf(PeerDiscoveryManager.props(discoveryListener, discoveryConfig,
      storagesInstance.storages.knownNodesStorage, nodeStatusHolder), "peer-discovery-manager")
}

trait DiscoveryListenerBuilder {
  self: ActorSystemBuilder
  with DiscoveryConfigBuilder
  with NodeStatusBuilder =>

  lazy val discoveryListener = actorSystem.actorOf(DiscoveryListener.props(discoveryConfig, nodeStatusHolder), "discovery-listener")
}

trait NodeStatusBuilder {

  self : NodeKeyBuilder =>

  private val nodeStatus =
    NodeStatus(
      key = nodeKey,
      serverStatus = ServerStatus.NotListening,
      discoveryStatus = ServerStatus.NotListening)

  lazy val nodeStatusHolder = Agent(nodeStatus)
}

trait BlockchainBuilder {
  self: StorageBuilder =>

  lazy val blockchain: BlockchainImpl = BlockchainImpl(storagesInstance.storages)

  lazy val genesisTimestamp: Long = blockchain.genesisSignedHeader.header.unixTimestamp
}

trait ForkResolverBuilder {
  self: BlockchainConfigBuilder =>

  lazy val forkResolverOpt = blockchainConfig.daoForkConfig.map(new ForkResolver.EtcForkResolver(_))

}

trait HandshakerBuilder {
  self: BlockchainBuilder
    with NodeStatusBuilder
    with StorageBuilder
    with PeerManagerActorBuilder
    with BlockchainConfigBuilder
    with ForkResolverBuilder =>

  private val handshakerConfiguration: EtcHandshakerConfiguration =
    new EtcHandshakerConfiguration {
      override val forkResolverOpt: Option[ForkResolver] = self.forkResolverOpt
      override val nodeStatusHolder: Agent[NodeStatus] = self.nodeStatusHolder
      override val peerConfiguration: PeerConfiguration = self.peerConfiguration
      override val blockchain: Blockchain = self.blockchain
      override val appStateStorage: AppStateStorage = self.storagesInstance.storages.appStateStorage
    }

  lazy val handshaker: Handshaker[PeerInfo] = EtcHandshaker(handshakerConfiguration)
}

trait AuthHandshakerBuilder {
  self: NodeKeyBuilder
  with SecureRandomBuilder =>

  lazy val authHandshaker: AuthHandshaker = AuthHandshaker(nodeKey, secureRandom)
}

trait PeerEventBusBuilder {
  self: ActorSystemBuilder =>

  lazy val peerEventBus = actorSystem.actorOf(PeerEventBusActor.props, "peer-event-bus")
}

trait PeerManagerActorBuilder {

  self: ActorSystemBuilder
    with HandshakerBuilder
    with PeerEventBusBuilder
    with AuthHandshakerBuilder
    with PeerDiscoveryManagerBuilder
    with StorageBuilder
    with KnownNodesManagerBuilder =>

  lazy val peerConfiguration = Config.Network.peer

  lazy val peerManager = actorSystem.actorOf(PeerManagerActor.props(
    peerDiscoveryManager,
    Config.Network.peer,
    peerEventBus,
    knownNodesManager,
    handshaker,
    authHandshaker,
    EthereumMessageDecoder), "peer-manager")

}

trait EtcPeerManagerActorBuilder {
  self: ActorSystemBuilder
    with PeerManagerActorBuilder
    with PeerEventBusBuilder
    with ForkResolverBuilder
    with StorageBuilder =>

  lazy val etcPeerManager = actorSystem.actorOf(EtcPeerManagerActor.props(
    peerManager, peerEventBus, storagesInstance.storages.appStateStorage, forkResolverOpt), "etc-peer-manager")

}

trait BlockchainHostBuilder {
  self: ActorSystemBuilder
    with BlockchainBuilder
    with PeerManagerActorBuilder
    with EtcPeerManagerActorBuilder
    with PeerEventBusBuilder =>

  val blockchainHost = actorSystem.actorOf(BlockchainHostActor.props(
    blockchain, peerConfiguration, peerEventBus, etcPeerManager), "blockchain-host")

}

trait ServerActorBuilder {

  self: ActorSystemBuilder
    with NodeStatusBuilder
    with BlockchainBuilder
    with PeerManagerActorBuilder =>

  lazy val networkConfig = Config.Network

  lazy val server = actorSystem.actorOf(ServerActor.props(nodeStatusHolder, peerManager), "server")

}

trait Web3ServiceBuilder {
  lazy val web3Service = new Web3Service
}

trait NetServiceBuilder {
  this: PeerManagerActorBuilder with NodeStatusBuilder =>

  lazy val netServiceConfig = NetServiceConfig(Config.config)

  lazy val netService = new NetService(nodeStatusHolder, peerManager, netServiceConfig)
}

trait PendingTransactionsManagerBuilder {
  self: ActorSystemBuilder
    with PeerManagerActorBuilder
    with EtcPeerManagerActorBuilder
    with PeerEventBusBuilder
    with TxPoolConfigBuilder =>

  lazy val pendingTransactionsManager: ActorRef = actorSystem.actorOf(PendingTransactionsManager.props(
    txPoolConfig, peerManager, etcPeerManager, peerEventBus))
}

trait FilterManagerBuilder {
  self: ActorSystemBuilder
    with BlockchainBuilder
    with BlockGeneratorBuilder
    with StorageBuilder
    with KeyStoreBuilder
    with PendingTransactionsManagerBuilder
    with FilterConfigBuilder
    with TxPoolConfigBuilder =>

  lazy val filterManager: ActorRef =
    actorSystem.actorOf(
      FilterManager.props(
        blockchain,
        blockGenerator,
        storagesInstance.storages.appStateStorage,
        keyStore,
        pendingTransactionsManager,
        filterConfig,
        txPoolConfig), "filter-manager")
}

trait BlockGeneratorBuilder {
  self: BlockchainConfigBuilder with
    ValidatorsBuilder with
    LedgerBuilder with
    MiningConfigBuilder with
    BlockchainBuilder with
    PersonalServiceBuilder =>

  lazy val blockGenerator = new BlockGenerator(
    blockchain,
    blockchainConfig,
    miningConfig,
    ledger,
    validators,
    blockHeaderSignerFn = personalService.signBlockHeader
  )
}

trait EthServiceBuilder {
  self: StorageBuilder with
    BlockchainBuilder with
    BlockGeneratorBuilder with
    BlockchainConfigBuilder with
    PendingTransactionsManagerBuilder with
    LedgerBuilder with
    ValidatorsBuilder with
    KeyStoreBuilder with
    SyncControllerBuilder with
    OmmersPoolBuilder with
    MiningConfigBuilder with
    FilterManagerBuilder with
    FilterConfigBuilder =>

  lazy val ethService = new EthService(blockchain, blockGenerator, storagesInstance.storages.appStateStorage,
    miningConfig, ledger, keyStore, pendingTransactionsManager, syncController, ommersPool, filterManager, filterConfig,
    blockchainConfig, Config.Network.protocolVersion)
}

trait PersonalServiceBuilder {
  self: KeyStoreBuilder with
    BlockchainBuilder with
    BlockchainConfigBuilder with
    PendingTransactionsManagerBuilder with
    StorageBuilder with
    TxPoolConfigBuilder =>

  lazy val personalService = new PersonalService(keyStore, blockchain, pendingTransactionsManager,
    storagesInstance.storages.appStateStorage, blockchainConfig, txPoolConfig)
}

trait KeyStoreBuilder {
  self: SecureRandomBuilder =>
  lazy val keyStore: KeyStore = new KeyStoreImpl(Config.keyStoreDir, secureRandom)
}

trait JSONRpcControllerBuilder {
  this: Web3ServiceBuilder with EthServiceBuilder with NetServiceBuilder with PersonalServiceBuilder =>

  lazy val jsonRpcController = new JsonRpcController(web3Service, netService, ethService, personalService, Config.Network.Rpc)
}

trait JSONRpcHttpServerBuilder {

  self: ActorSystemBuilder with BlockchainBuilder with JSONRpcControllerBuilder with SecureRandomBuilder =>

  lazy val jsonRpcServerConfig: JsonRpcServerConfig = Config.Network.Rpc

  lazy val maybeJsonRpcServer = JsonRpcServer(jsonRpcController, jsonRpcServerConfig, secureRandom)
}

trait OmmersPoolBuilder {
  self: ActorSystemBuilder with
    BlockchainBuilder with
    MiningConfigBuilder =>

  lazy val ommersPool: ActorRef = actorSystem.actorOf(OmmersPool.props(blockchain, miningConfig))
}

trait ValidatorsBuilder {
  self: BlockchainConfigBuilder
    with ElectionManagerBuilder
    with SlotTimeConverterBuilder
    with ClockBuilder =>

  lazy val validators = new Validators {
    val blockValidator: BlockValidator = BlockValidator
    val blockHeaderValidator: BlockHeaderValidator = new BlockHeaderValidatorImpl(blockchainConfig, electionManager, slotTimeConverter, clock)
    val ommersValidator: OmmersValidator = new OmmersValidatorImpl(blockchainConfig, blockHeaderValidator)
    val signedTransactionValidator: SignedTransactionValidator = new SignedTransactionValidatorImpl(blockchainConfig)
  }
}

trait LedgerBuilder {
  self: BlockchainConfigBuilder
    with BlockchainBuilder
    with SyncConfigBuilder
    with ValidatorsBuilder =>

  lazy val ledger: Ledger = new LedgerImpl(VM, blockchain, blockchainConfig, syncConfig, validators)
}

trait SyncControllerBuilder {

  self: ActorSystemBuilder with
    ServerActorBuilder with
    BlockchainBuilder with
    NodeStatusBuilder with
    StorageBuilder with
    BlockchainConfigBuilder with
    ValidatorsBuilder with
    LedgerBuilder with
    PeerEventBusBuilder with
    PendingTransactionsManagerBuilder with
    OmmersPoolBuilder with
    EtcPeerManagerActorBuilder with
    SyncConfigBuilder with
    ShutdownHookBuilder with
    SlotTimeConverterBuilder =>

  lazy val syncController = actorSystem.actorOf(
    SyncController.props(
      storagesInstance.storages.appStateStorage,
      blockchain,
      storagesInstance.storages.fastSyncStateStorage,
      ledger,
      validators,
      peerEventBus,
      pendingTransactionsManager,
      ommersPool,
      etcPeerManager,
      syncConfig,
      slotTimeConverter,
      () => shutdown()), "sync-controller")

}

trait ShutdownHookBuilder {

  def shutdown(): Unit

  lazy val shutdownTimeoutDuration = Config.shutdownTimeout

  Runtime.getRuntime.addShutdownHook(new Thread() {
    override def run(): Unit = {
      shutdown()
    }
  })
}

trait GenesisDataLoaderBuilder {
  self: BlockchainBuilder
    with StorageBuilder
    with BlockchainConfigBuilder =>

  lazy val genesisDataLoader = new GenesisDataLoader(blockchain, blockchainConfig)
}

trait SecureRandomBuilder {
  lazy val secureRandom: SecureRandom =
    Config.secureRandomAlgo.map(SecureRandom.getInstance).getOrElse(new SecureRandom())
}

trait ProofOfStakeMinerBuilder {
  self: ActorSystemBuilder
    with BlockchainBuilder
    with OmmersPoolBuilder
    with PendingTransactionsManagerBuilder
    with BlockGeneratorBuilder
    with SyncControllerBuilder
    with ElectionManagerBuilder
    with KeyStoreBuilder
    with MiningConfigBuilder =>

  lazy val miner: ActorRef = actorSystem.actorOf(ProofOfStakeMiner.props(
    blockchain,
    blockGenerator,
    ommersPool,
    pendingTransactionsManager,
    syncController,
    miningConfig,
    keyStore,
    electionManager))
}

trait SlotTimeConverterBuilder {
  self: OuroborosConfigBuilder
    with BlockchainBuilder =>

  private lazy val slot1StartingTime: FiniteDuration = genesisTimestamp.seconds

  lazy val slotTimeConverter: SlotTimeConverter = SlotTimeConverter(ouroborosConfig, slot1StartingTime)
}

trait ClockBuilder {
  self: NTPServiceBuilder =>

  lazy val clock: Clock = NTPClock(ntpService)
}

trait BeaconActorBuilder {
  self: ActorSystemBuilder
    with BlockchainBuilder
    with ProofOfStakeMinerBuilder
    with OuroborosConfigBuilder
    with ClockBuilder =>

  private lazy val systemStartTime: FiniteDuration = genesisTimestamp.seconds

  lazy val beacon: ActorRef = actorSystem.actorOf(BeaconActor.props(
    miner,
    ouroborosConfig.slotDuration,
    systemStartTime,
    clock), "beacon")
}

trait NTPServiceBuilder {

  private lazy val ntpServiceConfig = NTPServiceConfig(Config.config)

  lazy val ntpService = new NTPService(ntpServiceConfig)

}

trait Node extends NodeKeyBuilder
  with ActorSystemBuilder
  with StorageBuilder
  with BlockchainBuilder
  with NodeStatusBuilder
  with ForkResolverBuilder
  with HandshakerBuilder
  with PeerManagerActorBuilder
  with ServerActorBuilder
  with SyncControllerBuilder
  with Web3ServiceBuilder
  with EthServiceBuilder
  with NetServiceBuilder
  with PersonalServiceBuilder
  with KeyStoreBuilder
  with BlockGeneratorBuilder
  with ValidatorsBuilder
  with LedgerBuilder
  with JSONRpcControllerBuilder
  with JSONRpcHttpServerBuilder
  with ShutdownHookBuilder
  with GenesisDataLoaderBuilder
  with BlockchainConfigBuilder
  with PeerEventBusBuilder
  with PendingTransactionsManagerBuilder
  with OmmersPoolBuilder
  with MiningConfigBuilder
  with EtcPeerManagerActorBuilder
  with BlockchainHostBuilder
  with FilterManagerBuilder
  with FilterConfigBuilder
  with TxPoolConfigBuilder
  with SecureRandomBuilder
  with AuthHandshakerBuilder
  with PruningConfigBuilder
  with PeerDiscoveryManagerBuilder
  with DiscoveryConfigBuilder
  with DiscoveryListenerBuilder
  with KnownNodesManagerBuilder
  with SyncConfigBuilder
  with OuroborosConfigBuilder
  with ElectionManagerBuilder
  with ProofOfStakeMinerBuilder
  with SlotTimeConverterBuilder
  with BeaconActorBuilder
  with NTPServiceBuilder
  with ClockBuilder
  with CertificateAuthorityManagerBuilder
