"use strict";

const functions = require("firebase-functions");
const admin = require("firebase-admin");
admin.initializeApp();

const fcmTopic = "store";
const fcmPriority = "normal";
const fcmTtl = 1000 * 3600 * 24;
const fcmTypeNewApplication = "new_application";
const fcmTypeNewVersion = "new_version";

/**
 * Triggers when a new application is created and sends a notification to the corresponding topic.
 */
exports.notifyNewApplications = functions.database
  .ref("/store/applications/{applicationUid}")
  .onCreate((snapshot, context) => {
    const application = snapshot.val();
    const applicationUid = context.params.applicationUid;
    console.log("New application detected!", application.name, applicationUid);

    if (application.silent === true) {
      console.log("Silent application, don't send notifications.");
      return Promise.resolve();
    }

    const message = {
      topic: fcmTopic,
      android: {
        priority: fcmPriority,
        ttl: fcmTtl
      },
      data: {
        type: fcmTypeNewApplication,
        applicationKey: String(applicationUid),
        applicationName: String(application.name),
        applicationPackageName: String(application.packageName)
      }
    };

    // Image might not already be available (uploading in progress)
    if (application.image) {
      message.data.applicationImage = application.image;
    }
    console.log("Sending message:", message);
    return admin
      .messaging()
      .send(message)
      .then(response => {
        console.log("Successfully sent message:", response);
        return Promise.resolve(response);
      })
      .catch(error => {
        console.log("Error sending message:", error);
        return Promise.reject(error);
      });
  });

/**
 * Triggers when a new version is created and sends a notification to the corresponding topic.
 */
exports.notifyNewVersions = functions.database
  .ref("/store/versions/{applicationUid}/{versionUid}")
  .onCreate((snapshot, context) => {
    const version = snapshot.val();
    const applicationUid = context.params.applicationUid;
    const versionUid = context.params.versionUid;
    console.log("New version detected!", applicationUid, versionUid);
    if (version.silent === true) {
      console.log("Silent version, don't send notifications.");
      return Promise.resolve();
    }
    return admin
      .database()
      .ref(`/store/applications/${applicationUid}`)
      .once("value")
      .then(applicationSnapshot => {
        return Promise.resolve(applicationSnapshot.val());
      })
      .then(application => {
        const message = {
          topic: fcmTopic,
          android: {
            priority: fcmPriority,
            ttl: fcmTtl,
            collapseKey: applicationUid
          },
          data: {
            type: fcmTypeNewVersion,
            applicationKey: String(applicationUid),
            applicationName: String(application.name),
            applicationPackageName: String(application.packageName),
            versionKey: String(versionUid),
            versionName: String(version.name)
          }
        };
        // Image might not already be available (uploading in progress)
        if (application.image) {
          message.data.applicationImage = application.image;
        }
        console.log("Sending message:", message);
        return admin.messaging().send(message);
      })
      .then(response => {
        console.log("Successfully sent message:", response);
        return Promise.resolve(response);
      })
      .catch(error => {
        console.log("Error sending message:", error);
        return Promise.reject(error);
      });
  });
