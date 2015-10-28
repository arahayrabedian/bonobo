import com.amazonaws.regions.Regions
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClient
import com.amazonaws.services.dynamodbv2.document.DynamoDB
import controllers.{ OpenForm, Auth, Application }
import play.api.i18n.{ DefaultLangs, DefaultMessagesApi, MessagesApi }
import play.api.libs.ws.ning.NingWSComponents
import com.gu.googleauth.GoogleAuthConfig
import org.joda.time.Duration

import play.api.ApplicationLoader.Context
import play.api.BuiltInComponentsFromContext
import play.api.routing.Router
import router.Routes

import util.AWSConstants._
import store.Dynamo
import kong.KongClient

class AppComponents(context: Context) extends BuiltInComponentsFromContext(context) with NingWSComponents {
  import AppComponents._

  val awsRegion = Regions.fromName(configuration.getString("aws.region") getOrElse "eu-west-1")

  val dynamo = {
    val usersTable = configuration.getString("aws.dynamo.usersTableName") getOrElse "Bonobo-Users"
    val keysTable = configuration.getString("aws.dynamo.keysTableName") getOrElse "Bonobo-Keys"
    val client: AmazonDynamoDBClient = new AmazonDynamoDBClient(CredentialsProvider).withRegion(awsRegion)
    new Dynamo(new DynamoDB(client), usersTable, keysTable)
  }

  val kong = {
    def confString(key: String) = configuration.getString(key) getOrElse sys.error(s"Missing configuration key: $key")
    val apiAddress = confString("kong.apiAddress")
    val apiName = confString("kong.apiName")
    new KongClient(wsClient, apiAddress, apiName)
  }

  val googleAuthConfig = {
    def missingKey(description: String) =
      sys.error(s"$description missing. You can create an OAuth 2 client from the Credentials section of the Google dev console.")
    GoogleAuthConfig(
      clientId = configuration.getString("google.clientId") getOrElse missingKey("OAuth 2 client ID"),
      clientSecret = configuration.getString("google.clientSecret") getOrElse missingKey("OAuth 2 client secret"),
      redirectUrl = configuration.getString("google.redirectUrl") getOrElse missingKey("OAuth 2 callback URL"),
      domain = Some("guardian.co.uk"),
      maxAuthAge = Some(Duration.standardDays(90)),
      enforceValidity = true
    )
  }

  val messagesApi: MessagesApi = new DefaultMessagesApi(environment, configuration, new DefaultLangs(configuration))
  val appController = new Application(dynamo, kong, messagesApi, googleAuthConfig, true)
  val authController = new Auth(googleAuthConfig, wsApi)
  val openFormController = new OpenForm(dynamo, kong, messagesApi)
  val assets = new controllers.Assets(httpErrorHandler)
  val router: Router = new Routes(httpErrorHandler, appController, openFormController, authController, assets)
}

object AppComponents {

}
