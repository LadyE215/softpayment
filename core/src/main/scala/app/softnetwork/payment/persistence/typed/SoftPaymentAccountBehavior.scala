package app.softnetwork.payment.persistence.typed

import akka.actor.typed.{ActorRef, ActorSystem}
import akka.actor.typed.scaladsl.{ActorContext, TimerScheduler}
import akka.persistence.typed.scaladsl.Effect
import app.softnetwork.account.handlers.{DefaultGenerator, Generator}
import app.softnetwork.account.message._
import app.softnetwork.account.model.{BasicAccount, BasicAccountProfile, Principal, PrincipalType}
import app.softnetwork.account.persistence.typed.AccountBehavior
import app.softnetwork.payment.message.AccountMessages
import app.softnetwork.payment.message.AccountMessages.SoftPaymentSignup
import app.softnetwork.payment.message.SoftPaymentAccountEvents.{
  SoftPaymentAccountCreatedEvent,
  SoftPaymentAccountProviderRegisteredEvent,
  SoftPaymentAccountTokenRefreshedEvent,
  SoftPaymentAccountTokenRegisteredEvent
}
import app.softnetwork.payment.model.SoftPaymentAccount
import app.softnetwork.payment.spi.PaymentProviders
import app.softnetwork.persistence.typed._
import app.softnetwork.scheduler.message.SchedulerEvents.ExternalSchedulerEvent
import app.softnetwork.security.sha256

import java.time.Instant

trait SoftPaymentAccountBehavior extends AccountBehavior[SoftPaymentAccount, BasicAccountProfile] {
  _: Generator =>
  override protected def createAccount(
    entityId: String,
    cmd: SignUp
  )(implicit context: ActorContext[AccountCommand]): Option[SoftPaymentAccount] = {
    cmd match {
      case SoftPaymentSignup(_, _, provider, _, _) =>
        PaymentProviders.paymentProvider(provider).client match {
          case Some(client) =>
            SoftPaymentAccount(BasicAccount(cmd, Some(entityId)))
              .map(account =>
                account
                  .withClients(Seq(client.withClientApiKey(client.generateApiKey())))
                  .withSecondaryPrincipals(
                    account.secondaryPrincipals.filterNot(
                      _.`type` == PrincipalType.Other
                    ) :+ Principal(PrincipalType.Other, client.clientId)
                  )
              )
          case _ => None
        }
      case _ => SoftPaymentAccount(BasicAccount(cmd, Some(entityId)))
    }
  }

  override protected def createProfileUpdatedEvent(
    uuid: String,
    profile: BasicAccountProfile,
    loginUpdated: Option[Boolean]
  )(implicit context: ActorContext[AccountCommand]): ProfileUpdatedEvent[BasicAccountProfile] =
    BasicAccountProfileUpdatedEvent(uuid, profile, loginUpdated)

  override protected def createAccountCreatedEvent(
    account: SoftPaymentAccount
  )(implicit context: ActorContext[AccountCommand]): AccountCreatedEvent[SoftPaymentAccount] =
    SoftPaymentAccountCreatedEvent(account)

  /** @param entityId
    *   - entity identity
    * @param state
    *   - current state
    * @param command
    *   - command to handle
    * @param replyTo
    *   - optional actor to reply to
    * @return
    *   effect
    */
  override def handleCommand(
    entityId: String,
    state: Option[SoftPaymentAccount],
    command: AccountCommand,
    replyTo: Option[ActorRef[AccountCommandResult]],
    timers: TimerScheduler[AccountCommand]
  )(implicit
    context: ActorContext[AccountCommand]
  ): Effect[ExternalSchedulerEvent, Option[SoftPaymentAccount]] = {
    implicit val system: ActorSystem[_] = context.system
    command match {
      case AccountMessages.RegisterProvider(provider) =>
        state match {
          case Some(account) =>
            if (account.clients.exists(_.provider.providerId == provider.providerId)) {
              Effect.none.thenRun { _ =>
                AccountMessages.ProviderAlreadyRegistered ~> replyTo
              }
            } else {
              PaymentProviders.paymentProvider(provider).client match {
                case Some(client) =>
                  val updatedClient = client.withClientApiKey(client.generateApiKey())
                  accountKeyDao.addAccountKey(updatedClient.clientId, entityId)
                  Effect
                    .persist(
                      SoftPaymentAccountProviderRegisteredEvent(
                        updatedClient,
                        Instant.now()
                      )
                    )
                    .thenRun(_ => AccountMessages.ProviderRegistered(updatedClient) ~> replyTo)
                case _ =>
                  Effect.none.thenRun { _ =>
                    AccountMessages.ProviderNotRegistered ~> replyTo
                  }
              }
            }
          case _ =>
            Effect.none.thenRun { _ =>
              AccountNotFound ~> replyTo
            }
        }

      case AccountMessages.LoadClient(clientId) =>
        state match {
          case Some(account) =>
            account.clients.find(_.clientId == clientId) match {
              case Some(client) =>
                Effect.none.thenRun { _ =>
                  AccountMessages.ClientLoaded(client) ~> replyTo
                }
              case _ =>
                Effect.none.thenRun { _ =>
                  AccountMessages.ClientNotFound ~> replyTo
                }
            }
          case _ =>
            Effect.none.thenRun { _ =>
              AccountNotFound ~> replyTo
            }
        }

      case AccountMessages.ListApiKeys =>
        state match {
          case Some(account) =>
            Effect.none.thenRun { _ =>
              AccountMessages.ApiKeysLoaded(account.apiKeys) ~> replyTo
            }
          case _ =>
            Effect.none.thenRun { _ =>
              AccountNotFound ~> replyTo
            }
        }

      case AccountMessages.LoadApiKey(clientId) =>
        state match {
          case Some(account) =>
            account.apiKeys.find(_.clientId == clientId) match {
              case Some(apiKey) =>
                Effect.none.thenRun { _ =>
                  AccountMessages.ApiKeyLoaded(apiKey) ~> replyTo
                }
              case _ =>
                Effect.none.thenRun { _ =>
                  AccountMessages.ApiKeyNotFound ~> replyTo
                }
            }
          case _ =>
            Effect.none.thenRun { _ =>
              AccountNotFound ~> replyTo
            }
        }

      case AccountMessages.GenerateClientToken(
            clientId,
            clientSecret,
            scope
          ) => // grant_type=client_credentials
        state match {
          case Some(account) if account.status.isActive =>
            account.clients.find(_.clientId == clientId) match {
              case Some(client) if client.accessToken.exists(!_.expired) =>
                Effect.none.thenRun(_ => AccessTokenAlreadyExists ~> replyTo)

              case Some(client) if client.clientApiKey.isEmpty =>
                Effect.none.thenRun(_ => AccountMessages.ClientNotFound ~> replyTo)

              case Some(client) =>
                if (client.getClientApiKey == clientSecret) {
                  val accessToken =
                    generator.generateAccessToken(
                      account.primaryPrincipal.value,
                      scope
                    )
                  accountKeyDao.addAccountKey(accessToken.token, entityId)
                  accountKeyDao.addAccountKey(accessToken.refreshToken, entityId)
                  Effect
                    .persist(
                      SoftPaymentAccountTokenRegisteredEvent(
                        client.withAccessToken(
                          accessToken.copy(
                            token = sha256(accessToken.token),
                            refreshToken = sha256(accessToken.refreshToken)
                          )
                        ),
                        Instant.now()
                      )
                    )
                    .thenRun { _ =>
                      AccessTokenGenerated(accessToken) ~> replyTo
                    }
                } else {
                  Effect.none.thenRun { _ =>
                    AccountMessages.ClientNotFound ~> replyTo
                  }
                }

              case _ =>
                Effect.none.thenRun { _ =>
                  AccountMessages.ClientNotFound ~> replyTo
                }
            }

          case Some(account) if account.status.isDisabled =>
            Effect.none.thenRun(_ => AccountDisabled ~> replyTo)

          case Some(account) if account.status.isDeleted =>
            Effect.none.thenRun(_ => AccountDeleted(account) ~> replyTo)

          case _ =>
            Effect.none.thenRun { _ =>
              AccountNotFound ~> replyTo
            }
        }

      case AccountMessages.RefreshClientToken(refreshToken) =>
        state match {
          case Some(account) if account.status.isActive =>
            account.clients.find(
              _.accessToken.map(_.refreshToken).getOrElse("") == sha256(refreshToken)
            ) match {
              case Some(client) =>
                val previousToken = client.getAccessToken
                val accessToken =
                  generator.generateAccessToken(
                    account.primaryPrincipal.value,
                    previousToken.scope
                  )
                accountKeyDao.removeAccountKey(previousToken.token)
                accountKeyDao.removeAccountKey(previousToken.refreshToken)
                accountKeyDao.addAccountKey(accessToken.token, entityId)
                accountKeyDao.addAccountKey(accessToken.refreshToken, entityId)
                Effect
                  .persist(
                    SoftPaymentAccountTokenRefreshedEvent(
                      client.withAccessToken(
                        accessToken.copy(
                          token = sha256(accessToken.token),
                          refreshToken = sha256(accessToken.refreshToken)
                        )
                      ),
                      Instant.now()
                    )
                  )
                  .thenRun { _ =>
                    AccessTokenGenerated(accessToken) ~> replyTo
                  }

              case _ =>
                Effect.none.thenRun { _ =>
                  AccountMessages.ClientNotFound ~> replyTo
                }
            }

          case Some(account) if account.status.isDisabled =>
            Effect.none.thenRun(_ => AccountDisabled ~> replyTo)

          case Some(account) if account.status.isDeleted =>
            Effect.none.thenRun(_ => AccountDeleted(account) ~> replyTo)

          case _ =>
            Effect.none.thenRun { _ =>
              AccountNotFound ~> replyTo
            }
        }

      case AccountMessages.OAuthClient(token) =>
        state match {
          case Some(account) if account.status.isActive =>
            account.clients.find(
              _.accessToken.map(_.token).getOrElse("") == sha256(token)
            ) match {
              case Some(client) if client.getAccessToken.expired =>
                Effect.none.thenRun(_ => TokenExpired ~> replyTo)

              case Some(client) =>
                Effect.none.thenRun { _ =>
                  AccountMessages.OAuthClientSucceededResult(client) ~> replyTo
                }

              case _ =>
                Effect.none.thenRun { _ =>
                  AccountMessages.ClientNotFound ~> replyTo
                }
            }

          case Some(account) if account.status.isDisabled =>
            Effect.none.thenRun(_ => AccountDisabled ~> replyTo)

          case Some(account) if account.status.isDeleted =>
            Effect.none.thenRun(_ => AccountDeleted(account) ~> replyTo)

          case _ =>
            Effect.none.thenRun { _ =>
              AccountNotFound ~> replyTo
            }
        }

      case AccountMessages.RegisterProviderAccount(provider) =>
        state match {
          case Some(account) =>
            accountKeyDao.addAccountKey(provider.clientId, entityId)
            Effect
              .persist(
                SoftPaymentAccountProviderRegisteredEvent(
                  provider.client,
                  Instant.now()
                )
              )
              .thenRun(state =>
                AccountMessages.ProviderAccountRegistered(state.getOrElse(account)) ~> replyTo
              )
          case _ =>
            val account = provider.account
            accountKeyDao.addAccountKey(provider.clientId, entityId)
            Effect.persist(SoftPaymentAccountCreatedEvent(account)).thenRun { state =>
              AccountMessages.ProviderAccountRegistered(state.getOrElse(account)) ~> replyTo
            }
        }

      case _ => super.handleCommand(entityId, state, command, replyTo, timers)
    }
  }

  /** @param state
    *   - current state
    * @param event
    *   - event to hanlde
    * @return
    *   new state
    */
  override def handleEvent(
    state: Option[SoftPaymentAccount],
    event: ExternalSchedulerEvent
  )(implicit context: ActorContext[_]): Option[SoftPaymentAccount] = {
    event match {
      case SoftPaymentAccountProviderRegisteredEvent(client, lastUpdated) =>
        state.map(account => {
          account
            .withClients(account.clients.filterNot(_.clientId == client.clientId) :+ client)
            .withLastUpdated(lastUpdated)
        })

      case SoftPaymentAccountTokenRegisteredEvent(client, lastUpdated) =>
        state.map(account => {
          account
            .withClients(account.clients.filterNot(_.clientId == client.clientId) :+ client)
            .withLastUpdated(lastUpdated)
        })

      case SoftPaymentAccountTokenRefreshedEvent(client, lastUpdated) =>
        state.map(account => {
          account
            .withClients(account.clients.filterNot(_.clientId == client.clientId) :+ client)
            .withLastUpdated(lastUpdated)
        })

      case _ => super.handleEvent(state, event)
    }
  }

}

case object SoftPaymentAccountBehavior extends SoftPaymentAccountBehavior with DefaultGenerator {
  override def persistenceId: String = "SoftPaymentAccount"
}
