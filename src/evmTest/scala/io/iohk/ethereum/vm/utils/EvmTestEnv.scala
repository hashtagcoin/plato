package io.iohk.ethereum.vm.utils

import java.io.File
import io.iohk.ethereum.crypto._
import io.iohk.ethereum.domain.{Account, Address, UInt256}
import io.iohk.ethereum.vm._
import scala.util.Random
import scala.language.dynamics

object EvmTestEnv {
  val ContractsDir = new File("target/contracts")
}

// scalastyle:off magic.number
trait EvmTestEnv {

  import EvmTestEnv._

  val config = EvmConfig.PostEIP160ConfigBuilder(None)

  private var contractsAddresses: Map[String, Address] = Map.empty
  private var contractsAbis: Map[String, Seq[ABI]] = Map.empty

  private var internalWorld = MockWorldState()

  val defaultContractCreator = createAccount(balance = 1000)
  val defaultSender = createAccount(balance = 1000)

  def world: MockWorldState = internalWorld

  def contract(name: String): MockContract = {
    new MockContract(name, contractsAddresses(name))
  }

  def createAccount(balance: BigInt = 0): Address = {
    val newAddress = Address(Random.nextLong())
    internalWorld = world.saveAccount(newAddress, Account.empty().copy(balance = UInt256(balance)))
    newAddress
  }

  def deployContract(name: String,
                     creatorAddress: Address = defaultContractCreator,
                     value: BigInt = 0,
                     gasLimit: BigInt = BigInt(2).pow(256) - 1,
                     gasPrice: BigInt = 1,
                     constructorArgs: Seq[Any] = Nil): (ProgramResult[MockWorldState, MockStorage], MockContract) = {
    val creator = world.getAccount(creatorAddress).get

    val (contractAddress, worldAfterNonceIncrease) = world.createAddressWithOpCode(creatorAddress)

    val contractInitCode = Utils.loadContractCodeFromFile(new File(s"$ContractsDir/$name.bin"))
    val contractAbi = Utils.loadContractAbiFromFile(new File(s"$ContractsDir/$name.abi"))

    val payload = constructorArgs.map(Contract.parseArg).foldLeft(contractInitCode)(_ ++ _)

    val tx = MockVmInput.transaction(creatorAddress, payload, value, gasLimit, gasPrice)
    val bh = MockVmInput.blockHeader

    val context = ProgramContext[MockWorldState, MockStorage](tx, contractAddress, Program(payload), bh, worldAfterNonceIncrease, config)
    val result = VM.run(context)

    contractsAbis += (name -> contractAbi.right.get)
    contractsAddresses += (name -> contractAddress)

    internalWorld = result.world
      .saveAccount(contractAddress, Account(UInt256(0), UInt256(value), Account.EmptyStorageRootHash, kec256(result.returnData)))
      .saveCode(contractAddress, result.returnData)

    (result, new MockContract(name, contractAddress))
  }

  class MockContract(val name: String, val address: Address) extends Dynamic {

    def contract: Contract[MockWorldState, MockStorage] =
      Contract[MockWorldState, MockStorage](address, MockVmInput.blockHeader, internalWorld, contractsAbis(name), config)

    def applyDynamic(methodName: String)(args: Any*): MockContractMethodCall = {
      val methodCall = contract.applyDynamic(methodName)(args: _*)
      new MockContractMethodCall(methodCall)
    }

    def callMethod(methodName: String)(args: Any*): MockContractMethodCall = {
      val methodCall = contract.callMethod(methodName)(args: _*)
      new MockContractMethodCall(methodCall)
    }

    def storage: MockStorage = world.storages(address)
  }

  class MockContractMethodCall(contractMethodCall: ContractMethodCall[MockWorldState, MockStorage]) {

    def call(value: BigInt = 0,
             gasLimit: BigInt = BigInt(2).pow(256) - 1,
             gasPrice: BigInt = 1,
             sender: Address = defaultSender): ProgramResult[MockWorldState, MockStorage] = {
      val res = contractMethodCall.call(value, gasLimit, gasPrice, sender)
      internalWorld = res.world
      res
    }

  }

}
