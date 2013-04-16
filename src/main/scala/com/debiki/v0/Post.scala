/**
 * Copyright (c) 2012-2013 Kaj Magnus Lindberg (born 1979)
 */

package com.debiki.v0

import java.{util => ju}
import collection.{immutable => imm, mutable => mut}
import Prelude._
import PageParts._
import FlagReason.FlagReason
import com.debiki.v0.{PostActionPayload => PAP}



/** Takes into account all actions in pageParts that affect this post.
  *
  * Re reviews and approvals: A post can be:
  * Unreviewed (then not yet approved).
  * Preliminarily approved (by the computer, automatically — it guesses / uses heuristics).
  * Permanently reviewed (by a moderator, or a well-behaved user).
  * Authoritatively reviewed (by a moderator but not a well-behaved user).
  */
case class Post(
    pageParts: PageParts,
    private val state: PostState,
    private val isLoadedFromCache: Boolean = true)
  extends PostAction[PAP.CreatePost](pageParts, state.creationPostActionDto)
  with MaybeApproval {


  def this(pageParts: PageParts, creationAction: PostActionDto[PAP.CreatePost]) {
    this(pageParts, PostState.whenCreated(creationAction), isLoadedFromCache = false)
  }


  def parentId: String = payload.parentPostId

  def parentPost: Option[Post] =
    if (parentId == id) None
    else debate.getPost(parentId)


  def approval = payload.approval


  def isArticleOrConfig =
    id == PageParts.TitleId || id == PageParts.BodyId || id == PageParts.ConfigPostId


  def initiallyApproved: Boolean = {
    // If initially preliminarily auto approved, that approval is cancelled by
    // any contiguous and subsequent rejection. (And `lastApproval` takes
    // that cancellation into account.)
    lastApprovalDati.isDefined && approval.isDefined
  }


  lazy val (currentText: String, markup: String, approvedText: Option[String]) = _applyEdits


  /** The initial text of this post: either 1) when created, or 2) when loaded from cache
    * (if it was loaded from cache).
    */
  def textInitially: String = payload.text


  def unapprovedText: Option[String] =
    if (Some(currentText) != approvedText) Some(currentText)
    else None


  def actions: List[PostAction[_]] = page.getActionsByPostId(id)


   // This currently happens directly, hence + 0:
  def numDeletePostVotesPending = state.numDeletePostVotes.pending + 0
  def numDeleteTreeVotesPending = state.numDeleteTreeVotes.pending + 0
  def numDeleteVotesPending = numDeletePostVotesPending + numDeleteTreeVotesPending
  def numDeletesToReview = state.numDeletesToReview + 0
  def numUndeletesToReview = state.numUndeletesToReview + 0

  def numMoveVotesPending = state.numMoveVotes.pending + 0 // not implemented
  def numMovesToReview = state.numMovesToReview + 0 // not implemented
  def numUnmovesToReview = state.numUnmovesToReview + 0 // not implemented


  /**
   * Applies all edits and returns the resulting text and markup.
   *
   * Keep in sync with textAsOf in debiki.js.
   *    COULD make textAsOf understand changes in markup type,
   *    or the preview won't always work correctly.
   *
   * (It's not feasible to apply only edits that have been approved,
   * or only edits up to a certain dati. Because
   * then the state of all edits at that dati would have to be computed.
   * But at a given dati, an edit might or might not have been applied and
   * approved, and taking that into account results in terribly messy code.
   * Instead, use Page.splitByVersion(), to get a version of the page
   * at a certain point in time.)
   */
  private def _applyEdits: (String, String, Option[String]) = {
    // (If loaded from cache, payload.text and .markup have both been inited
    // to the cached text, which includes all edits up to the cached version.)
    var curText = payload.text
    var approvedText =
      if (lastApprovalDati.isDefined) state.lastApprovedText orElse Some(curText)
      else None
    var curMarkup = payload.markup
    // Loop through all edits and patch curText.
    for (edit <- editsAppliedAscTime) {
      curMarkup = edit.newMarkup.getOrElse(curMarkup)
      val patchText = edit.patchText
      if (patchText nonEmpty) {
        val newText = applyPatch(patchText, to = curText)
        curText = newText
        if (lastApprovalDati.map(edit.applicationDati.get.getTime <= _.getTime) == Some(true))
          approvedText = Some(curText)
      }
    }
    (curText, curMarkup, approvedText)
  }


  def where: Option[String] = payload.where


  def edits: List[Patch] = page.editsFor(id)


  lazy val (
      editsDeletedDescTime: List[Patch],
      editsPendingDescTime: List[Patch],
      editsAppliedDescTime: List[Patch],
      editsRevertedDescTime: List[Patch]) = {

    var deleted = List[Patch]()
    var pending = List[Patch]()
    var applied = List[Patch]()
    var reverted = List[Patch]()

    edits foreach { edit =>
      // (Cannot easily move the below `assert`s to Edit, because
      // the values they test are computed lazily.)

      // An edit cannot be both applied and reverted at the same time.
      assErrIf(edit.isApplied && edit.isReverted, "DwE4FW21")
      if (edit.isReverted) reverted ::= edit
      if (edit.isApplied) applied ::= edit

      // An edit can have been reverted and then deleted,
      // but an edit that is currently in effect cannot also be deleted.
      assErrIf(edit.isApplied && edit.isDeleted, "DwE09IJ3")
      if (edit.isDeleted) deleted ::= edit

      // An edit that has been applied and then reverted, is pending again.
      // But an edit that is in effect, or has been deleted, cannot be pending.
      assErrIf(edit.isApplied && edit.isPending, "DwE337Z2")
      assErrIf(edit.isDeleted && edit.isPending, "DwE8Z3B2")
      if (edit.isPending) pending ::= edit
    }

    (deleted.sortBy(- _.deletionDati.get.getTime),
       pending.sortBy(- _.creationDati.getTime),
       applied.sortBy(- _.applicationDati.get.getTime),
       reverted.sortBy(- _.revertionDati.get.getTime))
  }


  def editsAppliedAscTime = editsAppliedDescTime.reverse


  def lastEditApplied = editsAppliedDescTime.headOption

  def lastEditAppliedAt: Option[ju.Date] =
    lastEditApplied.flatMap(_.applicationDati) orElse state.lastEditAppliedAt

  def lastEditorId: Option[String] =
    lastEditApplied.map(_.userId) orElse state.lastEditorId


  def lastEditReverted = editsRevertedDescTime.headOption

  def lastEditRevertedAt: Option[ju.Date] =
    lastEditReverted.flatMap(_.revertionDati) orElse state.lastEditRevertedAt


  /** How many different people that have edited this post. If loaded from cache,
    * ignores edits that happened after the cached version.
    */
  def numDistinctEditors: Int = {
    // Since we don't store the ids of all editors over time in the database post state
    // cache, it's not possible to know if any more recent `editsAppliedDescTime` editor
    // id coincides with any of the `state.numDistinctEditors` earlier editors.
    math.max(
      state.numDistinctEditors,
      editsAppliedDescTime.map(_.userId).distinct.length)
  }


  def numPendingEditSuggestions =
    state.numEditSuggestions + editsPendingDescTime.length


  /** Counts edits applied to this post but that have not yet been reviewed,
    * not even auto-reviewed by the computer.
    */
  def numEditsAppliedUnreviewed = {
    val numNew =
      if (lastReviewDati.isEmpty) editsAppliedDescTime.length
      else (editsAppliedDescTime takeWhile { patch =>
        lastReviewDati.get.getTime < patch.applicationDati.get.getTime
      }).length
    state.numEditsAppliedUnreviewed + numNew
  }


  /** Counts the edits made to this post that 1) have been preliminarily
    * approved by the computer, but 2) not permanently approved (by some moderator).
    */
  def numEditsAppldPrelApproved = {
    val numNew =
      if (lastApprovalType != Some(Approval.Preliminary)) 0
      else {
        (editsAppliedDescTime takeWhile { patch =>
          val perhapsPrelApprvd =
            patch.applicationDati.get.getTime <= lastApprovalDati.get.getTime
          val isPermApprvd =
            if (lastPermanentApprovalDati.isEmpty) false
            else patch.applicationDati.get.getTime <= lastPermanentApprovalDati.get.getTime
          perhapsPrelApprvd && !isPermApprvd
        }).length
      }
    state.numEditsAppldPrelApproved + numNew
  }


  /** Edits done after the last review — ignoring preliminary auto approval reviews —
    * need to be reviewed.
    */
  def numEditsToReview = {
    val numNew = lastPermanentApprovalDati match {
      case None => editsAppliedDescTime.length
      case Some(permApprovalDati) =>
        (editsAppliedDescTime takeWhile { patch =>
          permApprovalDati.getTime < patch.applicationDati.get.getTime
        }).length
    }
    state.numEditsToReview + numNew
  }


  /** When the text was last edited, or when this post was created.
    */
  def textLastEditedAt : ju.Date =
    lastEditAppliedAt getOrElse creationDati


  /** When the text of this Post was last edited or reverted to an earlier version.
    */
  def textLastEditedOrRevertedAt : ju.Date = {
    val maxTime = math.max(
       lastEditAppliedAt.map(_.getTime).getOrElse(0: Long),
       lastEditRevertedAt.map(_.getTime).getOrElse(0: Long))
    if (maxTime == 0) creationDati else new ju.Date(maxTime)
  }


  /** When this post was last edited, reverted, deleted, collapsed, flagged, anything
    * except up/downvotes. Used when sorting posts in the activity list in the admin UI.
    *
    * Currently only considers edits (could fix...)
    */
  def lastActedUponAt = textLastEditedOrRevertedAt


  private lazy val _reviewsDescTime: List[PostActionOld with MaybeApproval] = {
    // (If a ReviewPostAction.approval.isEmpty, this Post was rejected.
    // An Edit.approval or EditApp.approval being empty, however,
    // means only that this Post has not yet been reviewed — so the Edit
    // or EditApp is simply ignored.)
    var explicitReviews = page.explicitReviewsOf(id).sortBy(-_.creationDati.getTime)
    var implicitApprovals = List[PostActionOld with MaybeApproval]()
    if (approval.isDefined)
      implicitApprovals ::= this
    for (edit <- edits) {
      if (edit.approval.isDefined)
        implicitApprovals ::= edit
      for (editApp <- page.editAppsByEdit(edit.id)) {
        // Ought to reuse PostActionsWrapper's wrapped actionDto:s rather than
        // creating new objects here.
        if (editApp.approval.isDefined)
          implicitApprovals ::= new ApplyPatchAction(page, editApp)
        for (editAppReview <- page.explicitReviewsOf(editApp)) {
          explicitReviews ::= editAppReview
        }

        // In the future, deletions (and any other actions?) should also
        // be considered:
        //for (deletion <- page.deletionsFor(editApp)) {
        //  if (deletion.approval.isDefined)
        //    implicitApprovals ::= deletion
        //  ... check explicit reviews of `deletion`.
        //}
      }

      // In the future, also consider deletions?
      //for (deletion <- page.deletionsFor(edit)) {
      //  if (deletion.approval.isDefined)
      //    implicitApprovals ::= deletion
      //  ... check explicit reviews of `deletion`.
      //}
    }

    // In the future, consider deletions of `this.action`?
    // for (deletion <- page.deletionsFor(action)) ...

    val allReviews = explicitReviews ::: implicitApprovals
    allReviews.sortBy(- _.creationDati.getTime)
  }


  /**
   * Moderators need only be informed about things that happened after
   * this dati.
   */
  def lastAuthoritativeReviewDati: Option[ju.Date] =
    lastAuthoritativeReview.map(_.creationDati) orElse state.lastAuthoritativeReviewDati


  def lastReviewDati: Option[ju.Date] =
    _reviewsDescTime.headOption.map(_.creationDati) orElse state.lastReviewDati


  def lastApprovalType: Option[Approval] =
    lastApproval.flatMap(_.approval) orElse state.lastApprovalType


  lazy val lastApproval: Option[PostActionOld with MaybeApproval] = {
    // A rejection cancels all earlier and contiguous preliminary auto
    // approvals, so loop through the reviews:
    var rejectionFound = false
    var reviewsLeft = _reviewsDescTime
    var lastApproval: Option[PostActionOld with MaybeApproval] = None
    while (reviewsLeft nonEmpty) {
      val review = reviewsLeft.head
      reviewsLeft = reviewsLeft.tail
      if (review.approval == Some(Approval.Preliminary) && rejectionFound) {
        // Ignore this approval — the rejection cancels it.
      } else if (review.approval.isEmpty) {
        rejectionFound = true
      } else {
        lastApproval = Some(review)
        reviewsLeft = Nil
      }
    }
    lastApproval
  }


  /**
   * The most recent review of this post by an admin or moderator.
   * (But not by seemingly well behaved users.)
   */
  def lastAuthoritativeReview: Option[PostActionOld with MaybeApproval] =
    _reviewsDescTime.find(review =>
       review.approval != Some(Approval.Preliminary) &&
       review.approval != Some(Approval.WellBehavedUser))


  def lastPermanentApproval: Option[PostActionOld with MaybeApproval] =
    _reviewsDescTime.find(review =>
        review.approval.isDefined &&
        review.approval != Some(Approval.Preliminary))


  def lastPermanentApprovalDati: Option[ju.Date] =
    lastPermanentApproval.map(_.creationDati) orElse state.lastPermanentApprovalDati

  /**
   * All actions that affected this Post and didn't happen after
   * this dati should be considered when rendering this Post.
   */
  def lastApprovalDati: Option[ju.Date] =
    lastApproval.map(_.creationDati) orElse state.lastApprovalDati


  def lastManualApprovalDati: Option[ju.Date] =
    _reviewsDescTime.find(_.approval == Some(Approval.Manual)).map(_.creationDati) orElse
      state.lastManualApprovalDati


  def lastReviewWasApproval: Option[Boolean] =
    if (lastReviewDati.isEmpty) None
    else Some(lastReviewDati == lastApprovalDati)


  /** Has this post been reviewed by a moderator, well behaved user or by the computer?
    */
  def currentVersionReviewed: Boolean = {
    // Use >= not > because a comment might be auto approved, and then
    // the approval dati equals the comment creationDati.
    val isReviewed = lastReviewDati.isDefined &&
      textLastEditedAt.getTime <= lastReviewDati.get.getTime
    if (isReviewed)
      assErrIf(numEditsAppliedUnreviewed != 0,
        "DwE408B0", o"""Bad state. Page ${page.pageId}, post $id,
          numEditsAppliedUnreviewed = $numEditsAppliedUnreviewed""")
    isReviewed
  }


  /** If this post has been reviewed by a moderator or in some cases a well
    * behaved user, it is considered permanently reviewed.
    */
  def currentVersionPermReviewed: Boolean =
    currentVersionReviewed && lastApprovalType != Some(Approval.Preliminary)


  /** Has the computer preliminarily approved this post, or the last few edits?
    */
  def currentVersionPrelApproved: Boolean =
    currentVersionReviewed && lastApprovalType == Some(Approval.Preliminary)


  def currentVersionApproved: Boolean =
    currentVersionReviewed && lastReviewWasApproval.get


  def currentVersionRejected: Boolean =
    currentVersionReviewed && !lastReviewWasApproval.get


  def someVersionApproved: Boolean = {
    // A rejection cancels all edits since the previous approval,
    // or effectively deletes the post, if it has never been approved.
    // To completely "unapprove" a post that has previously been approved,
    // delete it instead.
    lastApprovalDati.nonEmpty
  }


  def someVersionPermanentlyApproved: Boolean =
    lastPermanentApprovalDati.nonEmpty


  def someVersionManuallyApproved: Boolean =
    lastManualApprovalDati.isDefined


  lazy val depth: Int = {
    var depth = 0
    var curId = id
    var nextParent = page.getPost(parentId)
    while (nextParent.nonEmpty && nextParent.get.id != curId) {
      depth += 1
      curId = nextParent.get.id
      nextParent = page.getPost(nextParent.get.parentId)
    }
    depth
  }


  def replyCount: Int =
    debate.repliesTo(id).length


  def replies: List[Post] = debate.repliesTo(id)


  def siblingsAndMe: List[Post] = debate.repliesTo(parentId)


  // This currently happens directly, hence + 0:
  def numCollapsePostVotesPending = state.numCollapsePostVotes.pending + 0
  def numCollapseTreeVotesPending = state.numCollapseTreeVotes.pending + 0
  def numCollapseVotesPending = numCollapsePostVotesPending + numCollapseTreeVotesPending

  def numUncollapsePostVotesPending = state.numCollapsePostVotes.undoPending + 0
  def numUncollapseTreeVotesPending = state.numCollapseTreeVotes.undoPending + 0

  def numCollapsesToReview = state.numCollapsesToReview + 0
  def numUncollapsesToReview = state.numUncollapsesToReview + 0


  def isTreeCollapsed: Boolean =
    hasHappenedNotUndone(PostActionPayload.CollapseTree) ||
      state.collapsed == Some(PAP.CollapseTree)

  def isOnlyPostCollapsed: Boolean =
    hasHappenedNotUndone(PostActionPayload.CollapsePost) ||
      state.collapsed == Some(PAP.CollapsePost)


  private def hasHappenedNotUndone(payload: PostActionPayload): Boolean =
    actions.find { action =>
      action.payload == payload  // && !action.wasUndone
    }.nonEmpty


  /** How many people have up/downvoted this post. Might be a tiny bit
    * inaccurate, if a cached post state is relied on (because it doesn't
    * store the ids of all raters, and if you add yet another rater, we don't
    * know if you're adding a new rating, or changing any old one of yours).
    */
  def numDistinctRaters = 1234  // unimpleimented
    // state.numDistinctRaters + num new ratings ratings


  // COULD optimize this, do once for all flags.
  lazy val flags = debate.flags.filter(_.postId == this.id)


  def flagsDescTime: List[Flag] = flags.sortBy(- _.ctime.getTime)


  /**
   * Flags that were raised before the last review by a moderator have
   * already been reviewed.
   */
  lazy val (
      flagsPendingReview: List[Flag],
      flagsReviewed: List[Flag]) =
    flagsDescTime span { flag =>
      if (lastAuthoritativeReviewDati isEmpty) true
      else lastAuthoritativeReviewDati.get.getTime <= flag.ctime.getTime
    }

  def numPendingFlags = state.numPendingFlags + flagsPendingReview.length
  def numHandledFlags = state.numHandledFlags + flagsReviewed.length
  def numFlags = numPendingFlags + numHandledFlags

  lazy val lastFlag = flagsDescTime.headOption

  lazy val flagsByReason: imm.Map[FlagReason, List[Flag]] = {
    // Add reasons and flags to a mutable map.
    var mmap = mut.Map[FlagReason, mut.Set[Flag]]()
    for (f <- flags)
      mmap.getOrElse(f.reason, {
        val s = mut.Set[Flag]()
        mmap.put(f.reason, s)
        s
      }) += f
    // Copy to an immutable version.
    imm.Map[FlagReason, List[Flag]](
      (for ((reason, flags) <- mmap)
      yield (reason, flags.toList)).toList: _*)
  }

  /** Pairs of (FlagReason, flags-for-that-reason), sorted by
   *  number of flags, descending.
   */
  lazy val flagsByReasonSorted: List[(FlagReason, List[Flag])] = {
    flagsByReason.toList.sortWith((a, b) => a._2.length > b._2.length)
  }


  override def deletionDati: Option[ju.Date] =
    firstDelete.map(_.ctime) orElse state.deletedAt
    // ? Why does this recurse forever: super.deletionDati orElse state.deletedAt

}


// vim: fdm=marker et ts=2 sw=2 fo=tcqwn list
