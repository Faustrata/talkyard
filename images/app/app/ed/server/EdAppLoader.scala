package ed.server

import com.debiki.core._
import debiki.onebox.Onebox
import debiki.{Globals, RateLimiter, Nashorn, TextAndHtmlMaker}
import ed.server.http.{PlainApiActions, SafeActions}
import ed.server.security.EdSecurity
import play.{api => p}
import play.api._
import play.api.http.FileMimeTypes
import play.api.libs.ws.ahc.AhcWSComponents
import play.api.mvc.{ControllerComponents, EssentialFilter}
import play.api.routing.Router
import scala.concurrent.{ExecutionContext, Future}


class EdAppLoader extends ApplicationLoader {

  def load(context: ApplicationLoader.Context): Application = {
    LoggerConfigurator(context.environment.classLoader).foreach {
      _.configure(context.environment, context.initialConfiguration, Map.empty)
    }

    val isProd = context.environment.mode == play.api.Mode.Prod
    Globals.setIsProdForever(isProd)

    p.Logger.info("Starting... [EsMHELLO]")
    val app = new EdAppComponents(context).application
    p.Logger.info("Started. [EsMSTARTED]")
    app
  }

}

class EdAppComponents(appLoaderContext: ApplicationLoader.Context)
  extends BuiltInComponentsFromContext(appLoaderContext)
  with AhcWSComponents
  with _root_.controllers.AssetsComponents {

  actorSystem registerOnTermination {
    p.Logger.info("Akka actor system has shut down. [TyMACTRSGONE]")
  }

  // Could instead extend HttpFiltersComponents, but it adds a weird localhost-only filter.
  SECURITY // it adds some maybe-useful security related filters too, investigate if should use them.
  override def httpFilters: Seq[EssentialFilter] = Seq(EdFilters.makeGzipFilter(materializer))

  // Jaeger docs: https://github.com/yurishkuro/opentracing-tutorial/tree/master/java
  val tracer: io.jaegertracing.internal.JaegerTracer = {
    import io.jaegertracing.Configuration.JAEGER_SERVICE_NAME
    val tracerServiceName =
      Option(System.getProperty(JAEGER_SERVICE_NAME, System.getenv(JAEGER_SERVICE_NAME)))
        .getOrElse("ty-app")
    io.jaegertracing.Configuration.fromEnv(tracerServiceName).getTracer
  }

  val globals = new Globals(appLoaderContext, executionContext, wsClient, actorSystem, tracer)
  val security = new ed.server.security.EdSecurity(globals)
  val rateLimiter = new RateLimiter(globals, security)
  val safeActions = new SafeActions(globals, security, controllerComponents.parsers)
  val plainApiActions = new PlainApiActions(safeActions, globals, security, rateLimiter)

  val nashorn = new Nashorn(globals)
  val oneboxes = new Onebox(globals, nashorn)
  nashorn.setOneboxes(oneboxes)

  val context = new EdContext(
    globals, security, safeActions, plainApiActions, nashorn, oneboxes,
    materializer, controllerComponents)

  globals.setEdContext(context)
  globals.startStuff()

  applicationLifecycle.addStopHook { () =>
    Future.successful {
      p.Logger.info("Shutting down... [EsMBYESOON]")
      tracer.close()
      globals.stopStuff()
      p.Logger.info("Done shutting down. [EsMBYE]")
    }
  }

  // (Cannot:  import _root_.{controllers => c} because cannot incl _root_ in an import, apparently.)

  private def cc = controllerComponents

  val loginController = new _root_.controllers.LoginController(cc, context)
  val loginWithOpenAuthController = new _root_.controllers.LoginWithOpenAuthController(cc, context)

  lazy val router: Router = new _root_.router.Routes(
    httpErrorHandler,
    loginController,
    new _root_.controllers.LoginAsGuestController(cc, context),
    new _root_.controllers.LoginWithPasswordController(cc, context),
    loginWithOpenAuthController,
    new _root_.controllers.ImpersonateController(cc, context, loginController),
    new ed.server.pubsub.SubscriberController(cc, context),
    new _root_.controllers.EmbeddedTopicsController(cc, context),
    new _root_.controllers.SearchController(cc, context),
    new _root_.controllers.ResetPasswordController(cc, context),
    new _root_.controllers.CreateSiteController(cc, context),
    new _root_.controllers.AdminController(cc, context),
    new _root_.controllers.SettingsController(cc, context),
    new _root_.controllers.LegalController(cc, context),
    new _root_.controllers.SpecialContentController(cc, context),
    new _root_.controllers.ModerationController(cc, context),
    new _root_.controllers.UserController(cc, context),
    new _root_.controllers.UnsubscriptionController(cc, context),
    new ed.server.summaryemails.UnsubFromSummariesController(cc, context),
    new _root_.controllers.InviteController(cc, context),
    new _root_.controllers.ForumController(cc, context),
    new _root_.controllers.PageController(cc, context),
    new _root_.controllers.ReplyController(cc, context),
    new _root_.controllers.DraftsController(cc, context),
    new _root_.controllers.CustomFormController(cc, context),
    new ed.plugins.utx.UsabilityTestingExchangeController(cc, context),
    new _root_.controllers.VoteController(cc, context),
    new _root_.controllers.Application(cc, context),
    new _root_.controllers.EditController(cc, context),
    new _root_.controllers.PageTitleSettingsController(cc, context),
    new _root_.controllers.GroupTalkController(cc, context),
    new _root_.controllers.UploadsController(cc, context),
    new _root_.controllers.CloseCollapseController(cc, context),
    new _root_.controllers.ImportExportController(cc, context),
    new _root_.controllers.DebugTestController(cc, context),
    new _root_.controllers.SiteAssetBundlesController(cc, context),
    new _root_.controllers.TagsController(cc, context),
    new _root_.controllers.SuperAdminController(cc, context),
    new _root_.controllers.ApiSecretsController(cc, context),
    new _root_.controllers.ApiV0Controller(cc, context),
    new _root_.controllers.ViewPageController(cc, context),
    assets)

}


class EdContext(
  val globals: Globals,
  val security: EdSecurity,
  val safeActions: SafeActions,
  val plainApiActions: PlainApiActions,
  val nashorn: Nashorn,
  val oneboxes: Onebox,
  val akkaStreamMaterializer: akka.stream.Materializer,
  // Hide so fewer parts of the app get access to Play's internal stuff.
  private val controllerComponents: ControllerComponents) {

  val postRenderer = new talkyard.server.PostRenderer(nashorn)

  def rateLimiter: RateLimiter = plainApiActions.rateLimiter

  implicit def executionContext: ExecutionContext = controllerComponents.executionContext
  def mimeTypes: FileMimeTypes = controllerComponents.fileMimeTypes

}