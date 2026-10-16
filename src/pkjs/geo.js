/**
 * Carbon Ion — reverse-geocode name cache
 * (split out of index.js in 2.8.2; behaviour unchanged)
 *
 * @license GPL-3.0-or-later
 */
var C = require('./constants');
var GEONAME_CACHE_KEY = C.GEONAME_CACHE_KEY, GEONAME_TTL_MS = C.GEONAME_TTL_MS, GEONAME_COORD_PRECISION = C.GEONAME_COORD_PRECISION;
var wxsend = require('./wxsend');

/**
 * Round a coordinate for cache matching.
 *
 * @param   {number} value
 * @returns {number}
 */
function roundCoord(value) {
	return parseFloat(Number(value).toFixed(GEONAME_COORD_PRECISION));
}

/**
 * Return true when coordinates match at configured precision.
 *
 * @param   {number} latA
 * @param   {number} lonA
 * @param   {number} latB
 * @param   {number} lonB
 * @returns {boolean}
 */
function coordsMatchRounded(latA, lonA, latB, lonB) {
	return roundCoord(latA) === roundCoord(latB) &&
		roundCoord(lonA) === roundCoord(lonB);
}

/**
 * Read geocode name cache.
 *
 * @returns {{lat:number,lon:number,name:string,fetchedAt:number,static:boolean}|null}
 */
function readGeonameCache() {
	try {
		var raw = localStorage.getItem(GEONAME_CACHE_KEY);
		if (!raw) return null;
		var obj = JSON.parse(raw);
		if (!obj || typeof obj.name !== 'string') return null;
		if (!isFinite(obj.lat) || !isFinite(obj.lon)) return null;
		if (!isFinite(obj.fetchedAt)) return null;
		obj.static = !!obj.static;
		return obj;
	} catch (e) {
		return null;
	}
}

/**
 * Persist geocode name cache entry.
 *
 * @param {number}  lat
 * @param {number}  lon
 * @param {string}  name
 * @param {boolean} isStatic
 */
function writeGeonameCache(lat, lon, name, isStatic) {
	try {
		localStorage.setItem(GEONAME_CACHE_KEY, JSON.stringify({
			lat: lat,
			lon: lon,
			name: name,
			fetchedAt: Date.now(),
			static: !!isStatic,
		}));
	} catch (e) { }
}

/**
 * Return cached geocode name when valid for the current mode and coordinates.
 *
 * @param   {number}  lat
 * @param   {number}  lon
 * @param   {boolean} isStatic
 * @returns {string|null}
 */
function getCachedGeoname(lat, lon, isStatic) {
	var cache = readGeonameCache();
	if (!cache) return null;

	if (!coordsMatchRounded(lat, lon, cache.lat, cache.lon)) {
		return null;
	}

	if (isStatic && cache.static) {
		return cache.name;
	}

	if (Date.now() - cache.fetchedAt <= GEONAME_TTL_MS) {
		return cache.name;
	}

	return null;
}

/**
 * Extract last-known coordinates from weather cache payload.
 *
 * @returns {{lat:number,lon:number}|null}
 */
function getLastKnownCoordsFromCache() {
	var cache = wxsend.readCache();
	if (!cache || !cache.payload) return null;
	var lat = cache.payload.lat;
	var lon = cache.payload.lon;
	if (!isFinite(lat) || !isFinite(lon)) return null;
	return { lat: lat, lon: lon };
}

module.exports = { getCachedGeoname: getCachedGeoname, writeGeonameCache: writeGeonameCache, getLastKnownCoordsFromCache: getLastKnownCoordsFromCache };
