/**
 * Copyright (c) 2012 Kaj Magnus Lindberg (born 1979)
 */

package controllers

import com.debiki.v0._
import debiki._
import debiki.DebikiHttp._
import java.{util => ju}
import play.api._
import play.api.mvc.{Action => _, _}
import Prelude._
import Utils.ValidationImplicits._


/**
 */
abstract class DebikiRequest[A] {

  def sid: SidStatus
  def xsrfToken: XsrfOk
  def identity: Option[Identity]
  def user: Option[User]
  def dao: TenantDao
  def request: Request[A]

  require(dao.quotaConsumers.tenantId == tenantId)
  require(dao.quotaConsumers.ip == Some(ip))
  require(dao.quotaConsumers.roleId ==
     user.filter(_.isAuthenticated).map(_.id))

  def tenantId = dao.tenantId

  def loginId: Option[String] = sid.loginId

  /**
   * The login id of the user making the request. Throws 403 Forbidden
   * if not logged in (shouldn't happen normally).
   */
  def loginId_! : String =
    loginId getOrElse throwForbidden("DwE03kRG4", "Not logged in")

  def user_! : User =
    user getOrElse throwForbidden("DwE86Wb7", "Not logged in")

  def identity_! : Identity =
    identity getOrElse throwForbidden("DwE7PGJ2", "Not logged in")

  def userAsPeople: People = People() + _fakeLogin + identity_! + user_!

  protected def _fakeLogin = Login(
    id = loginId_!, prevLoginId = None, ip = request.remoteAddress,
    date = ctime, identityId = identity_!.id)

  /**
   * The display name of the user making the request. Throws 403 Forbidden
   * if not available, i.e. if not logged in (shouldn't happen normally).
   */
  def displayName_! : String =
    sid.displayName getOrElse throwForbidden("DwE97Ik3", "Not logged in")

  def session: mvc.Session = request.session

  def ip = request.remoteAddress

  /**
   * The end user's IP address, *iff* it differs from the login address.
   */
  def newIp: Option[String] = None  // None always, for now

  /**
   * Approximately when the server started serving this request.
   */
  lazy val ctime: ju.Date = new ju.Date

  /**
   * The scheme, host and port specified in the request.
   *
   * For now, the scheme is hardcoded to http.
   */
  def origin: String = "http://"+ request.host

  def host = request.host

  def uri = request.uri

  def queryString = request.queryString

  def body = request.body

  def isAjax = DebikiHttp.isAjax(request)

  def quotaConsumers = dao.quotaConsumers

}


/**
 * A request that's not related to any particular page.
 */
case class ApiRequest[A](
  sid: SidStatus,
  xsrfToken: XsrfOk,
  identity: Option[Identity],
  user: Option[User],
  dao: TenantDao,
  request: Request[A]) extends DebikiRequest[A] {
}


object PageRequest {

  /**
   * Builds a PageRequest based on another DebikiRequest and a page path.
   *
   * No attempt to correct the path is made. Instead, if the page exists,
   * but `pagePath` is incorrect, an error 404 Not Found exception is thrown.
   * (PageActions.CheckPathAction however redirects the browser to the correct
   * path, if you specify an almost correct path (e.g. superfluous
   * trailing '/').
   */
  def apply[A](apiRequest: DebikiRequest[A], pagePath: PagePath)
        : PageRequest[A] = {

    val pageExists = apiRequest.dao.checkPagePath(pagePath) match {
      case Some(correct: PagePath) =>
        if (correct.path != pagePath.path)
          throwNotFound("DwE305RI2", "Mismatching page path: "+ pagePath +
             ", should be: "+ correct)
        true
      case None =>
        false
    }

    // Dupl code, see PageActions.CheckPathAction
    val permsReq = RequestInfo(  // COULD RENAME! to PermsOnPageRequest
      tenantId = apiRequest.tenantId,
      ip = apiRequest.ip,
      loginId = apiRequest.sid.loginId,
      identity = apiRequest.identity,
      user = apiRequest.user,
      pagePath = pagePath)

    val permsOnPage = apiRequest.dao.loadPermsOnPage(permsReq)
    if (!permsOnPage.accessPage)
      throwForbidden("DwE72XIKW2", "You are not allowed to access that page.")

    PageRequest[A](
      sid = apiRequest.sid,
      xsrfToken = apiRequest.xsrfToken,
      identity = apiRequest.identity,
      user = apiRequest.user,
      pageExists = pageExists,
      pagePath = pagePath,
      permsOnPage = permsOnPage,
      dao = apiRequest.dao,
      request = apiRequest.request)()
  }

  def apply[A](apiRequest: DebikiRequest[A], pagePathStr: String,
        pageId: String) : PageRequest[A] = {
    val pagePathPerhapsId =
      PagePath.fromUrlPath(apiRequest.tenantId, pagePathStr) match {
        case PagePath.Parsed.Good(path) => path
        case x => throwBadReq(
          "DwE390SD3", "Bad page path for page id "+ pageId +": "+ x)
      }
    val pagePathWithId = pagePathPerhapsId.copy(pageId = Some(pageId))
    PageRequest(apiRequest, pagePathWithId)
  }
}


/**
 * A page related request.
 *
 * Sometimes only the browser ip is known (then there'd be no
 * Login/Identity/User).
 */
case class PageRequest[A](
  sid: SidStatus,
  xsrfToken: XsrfOk,
  identity: Option[Identity],
  user: Option[User],
  pageExists: Boolean,
  /** Ids of groups to which the requester belongs. */
  // userMemships: List[String],
  /** If the requested page does not exist, pagePath.pageId is empty. */
  pagePath: PagePath,
  permsOnPage: PermsOnPage,
  dao: TenantDao,
  request: Request[A])
  (private val _preloadedPageMeta: Option[PageMeta] = None,
  private val _preloadedActions: Option[Debate] = None)
  extends DebikiRequest[A] {

  require(pagePath.tenantId == tenantId) //COULD remove tenantId from pagePath
  require(!pageExists || pagePath.pageId.isDefined)


  def copyWithPreloadedPage(page: PageStuff, pageExists: Boolean)
        : PageRequest[A] = {
    copy(pageExists = pageExists, pagePath = page.path)(
      Some(page.meta), Some(page.actions))
  }


  def copyWithPreloadedPage(pageMeta: PageMeta, pageActions: Debate,
        pageExists: Boolean): PageRequest[A] = {
    require(pageMeta.pageId == pageActions.pageId)
    require(Some(pageMeta.pageId) == pagePath.pageId)
    copy(pageExists = pageExists)(Some(pageMeta), Some(pageActions))
  }


  def pageId: Option[String] = pagePath.pageId

  /**
   * Throws 404 Not Found if id unknown. The page id is known if it
   * was specified in the request, *or* if the page exists.
   */
  def pageId_! : String = pagePath.pageId getOrElse
    throwNotFound("DwE93kD4", "Page does not exist: "+ pagePath.path)

  /**
   * The page this PageRequest concerns, or None if not found
   * (e.g. if !pageExists, or if it was deleted just moments ago).
   */
  lazy val page_? : Option[Debate] =
    _preloadedActions orElse {
      if (pageExists) {
        pageId.flatMap(id => dao.loadPage(id))
      } else {
        // Don't load the page even if it was *created* moments ago.
        // having !pageExists and page_? = Some(..) feels risky.
        None
      }
    }

  /**
   * The page this PageRequest concerns. Throws 404 Not Found if not found.
   *
   * (The page might have been deleted, just after the access control step.)
   */
  // COULD rename to actions_!.
  lazy val page_! : Debate =
    page_? getOrElse throwNotFound("DwE43XWY", "Page not found")

  /**
   * Adds the current login, identity and user to page_!.people.
   * This is useful, if the current user does his/her very first
   * interaction with the page. Then page_!.people has no info
   * on that user, and an error would happen if you did something
   * with the page that required info on the current user.
   * (For example, adding [a reply written by the user] to the page,
   * and then rendering the page.)
   */
  lazy val pageWithMe_! : Debate = {
    // Could try not to add stuff that's already been added to page_!.people.
    // But anything we add should be fairly identical to anything that's
    // already there, so not very important?
    page_!.copy(people = page_!.people + _fakeLogin + identity_! + user_!)
  }

  /**
   * The page version is specified in the query string, e.g.:
   * ?view&version=2012-08-20T23:59:59Z&unapproved
   *
   * The default version is the most recent approved version.
   */
  lazy val pageVersion: PageVersion = {
    val approved = request.queryString.getFirst("unapproved").isEmpty
    request.queryString.getEmptyAsNone("version") match {
      case None => PageVersion.latest(approved)
      case Some(datiString) =>
        val dati = try {
          parseIso8601DateTime(datiString)
        } catch {
          case ex: IllegalArgumentException =>
            throwBadReq("DwE3DW27", "Bad version query param")
        }
        PageVersion(dati, approved)
    }
  }

  /**
   * The page root tells which post to start with when rendering a page.
   * By default, the page body is used. The root is specified in the
   * query string, like so: ?view=rootPostId  or ?edit=....&view=rootPostId
   */
  lazy val pageRoot: PageRoot =
    request.queryString.get("view").map(rootPosts => rootPosts.size match {
      case 1 => PageRoot(rootPosts.head)
      // It seems this cannot hapen with Play Framework:
      case 0 => assErr("DwE03kI8", "Query string param with no value")
      case _ => throwBadReq("DwE0k35", "Too many `view' query params")
    }) getOrElse PageRoot.TheBody


  def pageRole: PageRole = pageMeta.pageRole

  def parentPageId: Option[String] = pageMeta.parentPageId


  /**
   * Gets/loads page meta data from cache/database, or, if not found,
   * returns PageMeta.forUnknownPage.
   */
  // COULD cache!
  lazy val pageMeta: PageMeta = {
    _preloadedPageMeta getOrElse {
      if (pageExists) {
        pageId.flatMap(dao.loadPageMeta _) getOrElse PageMeta.forUnknownPage
      }
      else PageMeta.forUnknownPage
    }
  }

}


