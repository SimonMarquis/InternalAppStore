"use strict";

const cacheName = "store-1.0.6";

self.addEventListener("install", event => {
  event.waitUntil(
    caches
      .open(cacheName)
      .then(cache => cache.addAll(["./", "./index.html", "./index.css", "./index.js", "./favicon.ico", "./192.png", "./512.png"]))
      .then(self.skipWaiting())
  );
});

self.addEventListener("activate", event => {
  event.waitUntil(() => self.clients.claim());
});

self.addEventListener("fetch", event => {
  event.respondWith(
    caches
      .open(cacheName)
      .then(cache => cache.match(event.request))
      .then(response => response || fetch(event.request))
  );
});
