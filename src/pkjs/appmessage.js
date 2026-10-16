/**
 * Carbon Ion — AppMessage transport (retry + duplicate-send guard)
 * (split out of index.js in 2.8.2; behaviour unchanged)
 *
 * @license GPL-3.0-or-later
 */
var C = require('./constants');
var eventLog = require('./eventlog');
var SEND_RETRY_ATTEMPTS = C.SEND_RETRY_ATTEMPTS;
var SEND_RETRY_BASE_DELAY_MS = C.SEND_RETRY_BASE_DELAY_MS;

/**
 * Retry Pebble.sendAppMessage with exponential backoff.
 *
 * SEND_RETRY_ATTEMPTS is treated as retry count (in addition to the first
 * send), so base 1000ms yields delays of 1s/2s/4s.
 *
 * @param {Object} dict
 */
function sendToWatchWithRetry(dict) {
	var retriesLeft = SEND_RETRY_ATTEMPTS;
	var retryIndex = 0;

	function send() {
		Pebble.sendAppMessage(dict,
			function () {
				eventLog.log('send_ok', 'a=' + (retryIndex + 1));
				console.log('Carbon: weather sent to watch');
			},
			function (e) {
				if (retriesLeft <= 0) {
					eventLog.log('send_fail', 'a=' + (retryIndex + 1) +
						' err=' + JSON.stringify(e));
					console.log('Carbon: sendAppMessage failed: ' + JSON.stringify(e));
					return;
				}

				var delayMs = SEND_RETRY_BASE_DELAY_MS * Math.pow(2, retryIndex);
				eventLog.log('send_retry',
					'a=' + (retryIndex + 1) + ' d=' + delayMs +
					' err=' + JSON.stringify(e));
				console.log(
					'Carbon: send retry ' + (retryIndex + 1) + '/' + SEND_RETRY_ATTEMPTS +
					' in ' + delayMs + 'ms after error: ' + JSON.stringify(e)
				);
				retriesLeft -= 1;
				retryIndex += 1;
				setTimeout(send, delayMs);
			}
		);
	}

	send();
}

var s_lastSentAt = 0;
var s_lastSentSignature = '';

/** Send a dict unless the identical dict went out within SEND_DEDUPE_WINDOW_MS. */
function sendDeduped(dict) {
	var nowMs = Date.now();
	var signature = JSON.stringify(dict);
	if (s_lastSentAt > 0 && nowMs - s_lastSentAt < C.SEND_DEDUPE_WINDOW_MS &&
		signature === s_lastSentSignature) {
		eventLog.aggregate('dedupe_send', 'ms=' + (nowMs - s_lastSentAt));
		return false;
	}
	s_lastSentAt = nowMs;
	s_lastSentSignature = signature;
	sendToWatchWithRetry(dict);
	return true;
}

function resetDedupe() { s_lastSentAt = 0; s_lastSentSignature = ''; }

module.exports = { sendToWatchWithRetry: sendToWatchWithRetry, sendDeduped: sendDeduped, resetDedupe: resetDedupe };
