/**
 * Carbon Ion — HTTP helpers (masked errors, retries)
 * (split out of index.js in 2.8.2; behaviour unchanged)
 *
 * @license GPL-3.0-or-later
 */
var C = require('./constants');
var eventLog = require('./eventlog');
var XHR_TIMEOUT_MS = C.XHR_TIMEOUT_MS;

/** "https://host/…secret…" → "https://host/…" (calendar URLs are secrets). */
function maskUrl(url) {
	var m = /^([a-z][a-z0-9+.-]*:\/\/)?([^\/?#\s]*)/i.exec(String(url || '').trim());
	if (!m || !m[2]) return url ? '…' : '';
	return (m[1] || '') + m[2].replace(/^[^@]*@/, '') + '/…';
}

/** Mask every URL inside free text (event-log details, error strings). */
function maskUrlsIn(text) {
	if (typeof text !== 'string') return text;
	return text.replace(/(?:https?|webcal):\/\/[^\s"']+/gi, function (u) { return maskUrl(u); });
}

/* GET with optional request headers; allow304 treats 304 as success.
 * callback(err, text, info{status, header(name)}) */
function xhrRequest(url, opts, callback) {
	opts = opts || {};
	var xhr = new XMLHttpRequest();
	var settled = false;
	var dropped = [];
	function done(err, text) {
		if (settled) return;
		settled = true;
		callback(err, text, {
			status: xhr.status,
			droppedHeaders: dropped,
			header: function (n) { try { return xhr.getResponseHeader(n); } catch (e) { return null; } },
		});
	}
	xhr.onload = function () {
		if ((this.status >= 200 && this.status < 300) || (opts.allow304 && this.status === 304)) {
			done(null, this.responseText);
			return;
		}
		done('HTTP ' + this.status + ' for ' + maskUrl(url));
	};
	xhr.onerror = function () { done('XHR error for ' + maskUrl(url)); };
	xhr.ontimeout = function () { done('XHR timeout for ' + maskUrl(url)); };
	xhr.timeout = XHR_TIMEOUT_MS;
	xhr.open('GET', url);
	var hs = opts.headers || {};
	Object.keys(hs).forEach(function (k) {
		/* Browsers refuse User-Agent; PebbleKit JS runtimes may allow it. */
		try { if (hs[k]) xhr.setRequestHeader(k, hs[k]); } catch (e) { dropped.push(k); }
	});
	xhr.send();
}

function xhrGet(url, callback) {
	var xhr = new XMLHttpRequest();
	var settled = false;

	function done(err, responseText) {
		if (settled) return;
		settled = true;
		callback(err, responseText);
	}

	xhr.onload = function () {
		if (this.status >= 200 && this.status < 300) {
			done(null, this.responseText);
			return;
		}
		done('HTTP ' + this.status + ' for ' + maskUrl(url));
	};
	xhr.onerror = function () {
		done('XHR error for ' + maskUrl(url));
	};
	xhr.ontimeout = function () {
		done('XHR timeout for ' + maskUrl(url) + ' after ' + XHR_TIMEOUT_MS + 'ms');
	};
	xhr.timeout = XHR_TIMEOUT_MS;
	xhr.open('GET', url);
	xhr.send();
}

/**
 * Retry an XHR GET with exponential backoff.
 *
 * @param {string}   url
 * @param {number}   maxAttempts   Total attempts including the first try.
 * @param {number}   baseDelayMs   Backoff base delay in milliseconds.
 * @param {string}   label         Log label for this fetch type.
 * @param {Function} callback      Called with (err, responseText, validated).
 * @param {Object=}  events        Optional event code mapping for retry/ok/fail logs.
 * @param {Function=} validate     Optional validator: (responseText) => value or error string.
 */
function retryXhr(url, maxAttempts, baseDelayMs, label, callback, events, validate) {
	events = events || {};
	var attempt = 1;

	function run() {
		xhrGet(url, function (err, responseText) {
			var validated = null;

			if (!err && typeof validate === 'function') {
				try {
					var validationResult = validate(responseText);
					if (typeof validationResult === 'string' && validationResult.length > 0) {
						err = validationResult;
					} else {
						validated = validationResult;
					}
				} catch (e) {
					err = 'validate error for ' + label + ': ' + e;
				}
			}

			if (!err) {
				if (events.ok) {
					eventLog.log(events.ok, label + ' a=' + attempt);
				}
				callback(null, responseText, validated);
				return;
			}

			if (attempt >= maxAttempts) {
				if (events.fail) {
					eventLog.log(events.fail,
						label + ' a=' + attempt + ' err=' + err);
				}
				callback(err);
				return;
			}

			var delayMs = baseDelayMs * Math.pow(2, attempt - 1);
			if (events.retry) {
				eventLog.log(events.retry,
					label + ' a=' + attempt + ' d=' + delayMs +
					' err=' + err);
			}
			console.log(
				'Carbon: ' + label + ' retry ' + attempt + '/' + (maxAttempts - 1) +
				' in ' + delayMs + 'ms after error: ' + err
			);
			attempt += 1;
			setTimeout(run, delayMs);
		});
	}

	run();
}

module.exports = { maskUrl: maskUrl, maskUrlsIn: maskUrlsIn, xhrRequest: xhrRequest, xhrGet: xhrGet, retryXhr: retryXhr };
