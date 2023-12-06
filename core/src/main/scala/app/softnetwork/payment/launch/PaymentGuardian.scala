package app.softnetwork.payment.launch

import akka.actor.typed.ActorSystem
import app.softnetwork.account.handlers.AccountDao
import app.softnetwork.account.launch.AccountGuardian
import app.softnetwork.account.model.BasicAccountProfile
import app.softnetwork.account.persistence.typed.AccountBehavior
import app.softnetwork.api.server.GrpcService
import app.softnetwork.payment.PaymentCoreBuildInfo
import app.softnetwork.payment.api.{PaymentGrpcService, PaymentServer}
import app.softnetwork.payment.handlers.SoftPaymentAccountDao
import app.softnetwork.payment.model.SoftPaymentAccount
import app.softnetwork.payment.persistence.data.paymentKvDao
import app.softnetwork.payment.persistence.query.{
  PaymentCommandProcessorStream,
  Scheduler2PaymentProcessorStream
}
import app.softnetwork.payment.persistence.typed.{PaymentBehavior, SoftPaymentAccountBehavior}
import app.softnetwork.payment.spi.PaymentProviders
import app.softnetwork.persistence.launch.PersistentEntity
import app.softnetwork.persistence.query.EventProcessorStream
import app.softnetwork.persistence.schema.SchemaProvider
import app.softnetwork.persistence.typed.Singleton
import app.softnetwork.session.CsrfCheck

import scala.concurrent.ExecutionContext

trait PaymentGuardian extends AccountGuardian[SoftPaymentAccount, BasicAccountProfile] {
  _: SchemaProvider with CsrfCheck =>

  import app.softnetwork.persistence.launch.PersistenceGuardian._

  def paymentBehavior: ActorSystem[_] => PaymentBehavior = _ => PaymentBehavior

  override def accountBehavior
    : ActorSystem[_] => AccountBehavior[SoftPaymentAccount, BasicAccountProfile] = _ =>
    SoftPaymentAccountBehavior

  def paymentEntities: ActorSystem[_] => Seq[PersistentEntity[_, _, _, _]] = sys =>
    Seq(
      paymentBehavior(sys)
    )

  /** initialize all entities
    */
  override def entities: ActorSystem[_] => Seq[PersistentEntity[_, _, _, _]] = sys =>
    sessionEntities(sys) ++ accountEntities(sys) ++ paymentEntities(sys)

  /** initialize all singletons
    */
  override def singletons: ActorSystem[_] => Seq[Singleton[_]] = _ => Seq(paymentKvDao)

  def paymentCommandProcessorStream: ActorSystem[_] => PaymentCommandProcessorStream

  def scheduler2PaymentProcessorStream: ActorSystem[_] => Scheduler2PaymentProcessorStream

  def paymentEventProcessorStreams: ActorSystem[_] => Seq[EventProcessorStream[_]] = sys =>
    Seq(paymentCommandProcessorStream(sys)) :+ scheduler2PaymentProcessorStream(sys)

  /** initialize all event processor streams
    */
  override def eventProcessorStreams: ActorSystem[_] => Seq[EventProcessorStream[_]] = sys =>
    paymentEventProcessorStreams(sys) ++ accountEventProcessorStreams(sys)

  override def systemVersion(): String =
    sys.env.getOrElse("VERSION", PaymentCoreBuildInfo.version)

  def softPaymentAccountDao: SoftPaymentAccountDao = SoftPaymentAccountDao

  final override def accountDao: AccountDao = softPaymentAccountDao

  def paymentServer: ActorSystem[_] => PaymentServer = system => PaymentServer(system)

  def paymentGrpcServices: ActorSystem[_] => Seq[GrpcService] = system =>
    Seq(
      new PaymentGrpcService(paymentServer(system), softPaymentAccountDao)
    )

  def registerProvidersAccount: ActorSystem[_] => Unit = system => {
    PaymentProviders.defaultPaymentProviders.foreach(provider => {
      implicit val ec: ExecutionContext = system.executionContext
      softPaymentAccountDao.registerProviderAccount(provider)(system) map {
        case Some(account) =>
          system.log.info(s"Registered provider account for ${provider.providerId}: $account")
        case _ =>
          system.log.warn(s"Failed to register provider account for ${provider.providerId}")
      }
    })
  }

  override def initSystem: ActorSystem[_] => Unit = system => {
    registerProvidersAccount(system)
    super.initSystem(system)
  }

  override def banner: String =
    """
      |█████████              ██████   █████    ███████████                                                            █████
      | ███░░░░░███            ███░░███ ░░███    ░░███░░░░░███                                                          ░░███
      |░███    ░░░   ██████   ░███ ░░░  ███████   ░███    ░███  ██████   █████ ████ █████████████    ██████  ████████   ███████
      |░░█████████  ███░░███ ███████   ░░░███░    ░██████████  ░░░░░███ ░░███ ░███ ░░███░░███░░███  ███░░███░░███░░███ ░░░███░
      | ░░░░░░░░███░███ ░███░░░███░      ░███     ░███░░░░░░    ███████  ░███ ░███  ░███ ░███ ░███ ░███████  ░███ ░███   ░███
      | ███    ░███░███ ░███  ░███       ░███ ███ ░███         ███░░███  ░███ ░███  ░███ ░███ ░███ ░███░░░   ░███ ░███   ░███ ███
      |░░█████████ ░░██████   █████      ░░█████  █████       ░░████████ ░░███████  █████░███ █████░░██████  ████ █████  ░░█████
      | ░░░░░░░░░   ░░░░░░   ░░░░░        ░░░░░  ░░░░░         ░░░░░░░░   ░░░░░███ ░░░░░ ░░░ ░░░░░  ░░░░░░  ░░░░ ░░░░░    ░░░░░
      |                                                                   ███ ░███
      |                                                                  ░░██████
      |                                                                   ░░░░░░
      |""".stripMargin
}
