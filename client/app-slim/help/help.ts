/*
 * Copyright (C) 2015-2016 Kaj Magnus Lindberg
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

/// <reference path="../prelude.ts" />
/// <reference path="../ReactStore.ts" />
/// <reference path="../utils/utils.ts" />
/// <reference path="../utils/react-utils.ts" />
/// <reference path="../rules.ts" />
/// <reference path="../widgets.ts" />
/// <reference path="../more-bundle-not-yet-loaded.ts" />

//------------------------------------------------------------------------------
  namespace debiki2.help {  // RENAME to  talkyard.tips
//------------------------------------------------------------------------------

const r = ReactDOMFactories;


export function isHelpMessageClosedAnyVersion(store: Store, messageId: string): boolean {
  return store.me && !!store.me.closedHelpMessages[messageId];
}

export function isHelpMessageClosed(store: Store, message: HelpMessage) {
  if (!store.me) return false;
  const closedVersion = store.me.closedHelpMessages[message.id];
  return closedVersion && closedVersion === message.version;
}


export const HelpMessageBox = createComponent({   // RENAME to TipsBox
  mixins: [StoreListenerMixin],

  getInitialState: function() {
    return this.computeState();
  },

  componentWillUnmount: function() {
    this.isGone = true;
  },

  onChange: function() {
    // A store change event might invoke this, although unmounted. [MIXINBUG]
    if (this.isGone) return;
    this.setState(this.computeState());
  },

  computeState: function() {
    const message: HelpMessage = this.props.message;
    const store: Store = ReactStore.allData();
    const me: Myself = store.me;
    if (!store.userSpecificDataAdded) {
      // Don't want search engines to index help text.
      return { hidden: true };
    }
    const closedMessages: { [id: string]: number } = me.closedHelpMessages || {};
    const thisMessClosedVersion = closedMessages[message.id];
    return { hidden: thisMessClosedVersion === message.version };
  },

  hideThisHelp: function() {
    ReactActions.hideHelpMessages(this.props.message);

    const store: Store = ReactStore.allData();
    const me: Myself = store.me;
    const closedMessages: { [id: string]: number } = me.closedHelpMessages || {};
    const numClosed = _.size(closedMessages);
    const minNumClosedToShowUnhideTips = 3;

    // Wait a short while with opening this, so one first sees the effect of clicking Close.
    // Also, wait until one has clicked 3? Close buttons — to me, it otherwise feels annoying
    // that this tips pops up directly, and I have to close it too.
    if (this.props.showUnhideTips !== false && numClosed >= minNumClosedToShowUnhideTips) {
      setTimeout(() => morebundle.openHelpDialogUnlessHidden({
        content: r.span({}, t.help.YouCanShowAgain_1, r.b({}, t.help.YouCanShowAgain_2), '.'),
        id: '5YK7EW3',
      }), 550);
    }
  },

  render: function() {
    if (this.state.hidden && !(this.props.alwaysShow || this.props.message.alwaysShow))
      return null;

    // If there are more help dialogs afterwards, show a comment icon instead to give
    // the impression that we're talking with the computer. Only when no more help awaits,
    // show the close (well "cancel") icon.
    const okayIcon = this.props.message.moreHelpAwaits ? 'icon-comment' : 'icon-cancel';
    const okayButton = this.props.message.alwaysShow
        ? null
        : r.a({ className: okayIcon + ' dw-hide', onClick: this.hideThisHelp },
            this.props.message.okayText || t.Hide);

    const className = this.props.className || this.props.message.className || '';
    const largeClass = this.props.large ? ' dwHelp-large' : '';
    const warningClass = this.props.message.isWarning ? ' esHelp-warning' : '';
    const classes = className + ' dw-help' + largeClass + warningClass;
    return (
      r.div({ className: classes },
        r.div({ className: 'dw-help-text' },
          this.props.message.content),
        okayButton));
  }
});


//------------------------------------------------------------------------------
   }
//------------------------------------------------------------------------------
// vim: fdm=marker et ts=2 sw=2 tw=0 list
