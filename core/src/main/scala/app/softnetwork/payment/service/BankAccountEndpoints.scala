package app.softnetwork.payment.service

import app.softnetwork.payment.config.PaymentSettings
import app.softnetwork.payment.handlers.GenericPaymentHandler
import app.softnetwork.payment.message.PaymentMessages._
import app.softnetwork.payment.model.{BankAccountView, PaymentAccount}
import app.softnetwork.session.model.{SessionData, SessionDataDecorator}
import sttp.capabilities
import sttp.capabilities.akka.AkkaStreams
import sttp.model.StatusCode
import sttp.tapir.json.json4s.jsonBody
import sttp.tapir.server.ServerEndpoint

import scala.concurrent.Future

trait BankAccountEndpoints[SD <: SessionData with SessionDataDecorator[SD]] {
  _: RootPaymentEndpoints[SD] with GenericPaymentHandler =>

  import app.softnetwork.serialization._

  val createOrUpdateBankAccount: ServerEndpoint[Any with AkkaStreams, Future] =
    secureEndpoint.post
      .in(PaymentSettings.BankRoute)
      .in(jsonBody[BankAccountCommand].description("Legal or natural user bank account"))
      .out(
        statusCode(StatusCode.Ok)
          .and(jsonBody[BankAccountCreatedOrUpdated].description("Bank account created or updated"))
      )
      .serverLogic(session => { bank =>
        import bank._
        var externalUuid: String = ""
        val updatedUser: Option[PaymentAccount.User] = {
          user match {
            case Left(naturalUser) =>
              var updatedNaturalUser = {
                if (naturalUser.externalUuid.trim.isEmpty) {
                  naturalUser.withExternalUuid(session.id)
                } else {
                  naturalUser
                }
              }
              session.profile match {
                case Some(profile) if updatedNaturalUser.profile.isEmpty =>
                  updatedNaturalUser = updatedNaturalUser.withProfile(profile)
                case _ =>
              }
              externalUuid = updatedNaturalUser.externalUuid
              Some(PaymentAccount.User.NaturalUser(updatedNaturalUser))
            case Right(legalUser) =>
              var updatedLegalRepresentative = legalUser.legalRepresentative
              if (updatedLegalRepresentative.externalUuid.trim.isEmpty) {
                updatedLegalRepresentative = updatedLegalRepresentative.withExternalUuid(session.id)
              }
              session.profile match {
                case Some(profile) if updatedLegalRepresentative.profile.isEmpty =>
                  updatedLegalRepresentative = updatedLegalRepresentative.withProfile(profile)
                case _ =>
              }
              externalUuid = updatedLegalRepresentative.externalUuid
              Some(
                PaymentAccount.User.LegalUser(
                  legalUser.withLegalRepresentative(updatedLegalRepresentative)
                )
              )
          }
        }
        run(
          CreateOrUpdateBankAccount(
            externalUuidWithProfile(session),
            bankAccount.withExternalUuid(externalUuid),
            updatedUser,
            acceptedTermsOfPSP
          )
        ).map {
          case r: BankAccountCreatedOrUpdated => Right(r)
          case other                          => Left(error(other))
        }
      })
      .description("Create or update legal or natural user bank account")

  val loadBankAccount: ServerEndpoint[Any with AkkaStreams, Future] =
    secureEndpoint.get
      .in(PaymentSettings.BankRoute)
      .out(
        statusCode(StatusCode.Ok).and(
          jsonBody[BankAccountView]
            .description("Authenticated user bank account")
        )
      )
      .serverLogic(session =>
        _ => {
          run(
            LoadBankAccount(externalUuidWithProfile(session))
          ).map {
            case r: BankAccountLoaded => Right(r.bankAccount.view)
            case other                => Left(error(other))
          }
        }
      )
      .description("Load authenticated user bank account")

  val deleteBankAccount: ServerEndpoint[Any with AkkaStreams, Future] =
    secureEndpoint.delete
      .in(PaymentSettings.BankRoute)
      .out(
        statusCode(StatusCode.Ok).and(jsonBody[BankAccountDeleted.type])
      )
      .serverLogic(session =>
        _ =>
          run(DeleteBankAccount(externalUuidWithProfile(session), Some(false))).map {
            case BankAccountDeleted => Right(BankAccountDeleted)
            case other              => Left(error(other))
          }
      )
      .description("Delete authenticated user bank account")

  val bankAccountEndpoints: List[ServerEndpoint[AkkaStreams with capabilities.WebSockets, Future]] =
    List(
      createOrUpdateBankAccount,
      loadBankAccount,
      deleteBankAccount
    )
}
