package app.softnetwork.payment.api

import app.softnetwork.persistence.jdbc.schema.JdbcSchemaTypes
import app.softnetwork.persistence.schema.SchemaType
import org.slf4j.{Logger, LoggerFactory}

object MangoPayPostgresLauncher extends MangoPayApi {
  lazy val log: Logger = LoggerFactory getLogger getClass.getName

  override val schemaType: SchemaType = JdbcSchemaTypes.Postgres
}
