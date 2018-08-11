"use strict";

/* Copyright 2018 Simon Marquis */

AppStore.CONFIG = {
  allowAnonymousUsers: false,
  allowUnverifiedEmail: false,
  firebaseUi: {
    signInFlow: "popup",
    credentialHelper: firebaseui.auth.CredentialHelper.NONE,
    signInOptions: [
      firebase.auth.GoogleAuthProvider.PROVIDER_ID,
      {
        provider: firebase.auth.EmailAuthProvider.PROVIDER_ID,
        requireDisplayName: false
      }
    ]
  },
  apkMaxSize: 100 * 1024 * 1024 /* 100 MiB */,
  apkMimeType: "application/vnd.android.package-archive",
  imageMaxSize: 2 * 1024 * 1024 /* 2 MiB */,
  imageMimeType: "image/*"
};

AppStore.CONSTANTS = {
  packageNameRegex: /^([A-Za-z]{1}[A-Za-z\d_]*\.)*[A-Za-z][A-Za-z\d_]*$/,
  shrug: "¯\\_(ツ)_/¯"
};

// Un-comment this next line to enable Firebase debug logs
// firebase.database.enableLogging(true, true);

function AppStore() {
  this.initUserInterface();
  this.initInternalData();
  this.initFirebase();
  this.initRouter();

  Utils.preventDefaultDrop();
  Utils.initTimestampTimer();
}

AppStore.prototype.initInternalData = function(snapshot) {
  this.applications = {
    _data: [],
    get: key => this.applications._data[key],
    set: (key, value) => {
      value.key = key;
      this.applications._data[key] = value;
    },
    delete: key => delete this.applications._data[key]
  };
  this.versions = {
    _data: [],
    get: key => this.versions._data[key],
    set: (key, value) => {
      value.key = key;
      this.versions._data[key] = value;
    },
    delete: key => delete this.versions._data[key]
  };
};

AppStore.prototype.updateInternalData = function(snapshot) {
  this.applications._data = [];
  this.versions._data = [];

  Ui.empty(this.ui.applicationsCards, this.ui.applicationsDetails);
  Ui.hide(this.ui.newAppCard);
  this.ui.applicationsCards.append(this.ui.newAppCard);

  if (snapshot) {
    const store = snapshot.val();
    if (store && store.applications) {
      for (const [applicationKey, application] of Object.entries(
        store.applications
      )) {
        this.applications.set(applicationKey, application);
        if (store.versions) {
          for (const [versionKey, version] of Object.entries(
            store.versions[applicationKey] || {}
          )) {
            this.versions.set(versionKey, version);
          }
        }
      }
    } else {
      this.uiTriggerAlert(
        "alert-secondary",
        `<b>${
          AppStore.CONSTANTS.shrug
        }</b><br>The App Store is currently empty…`
      );
    }
  }
};

AppStore.prototype.initUserInterface = function() {
  this.templates = {
    applicationCard: document.getElementById("template-app-card"),
    applicationDetails: document.getElementById("template-app-details"),
    applicationLinkOutline: document.getElementById(
      "template-app-link-outline"
    ),
    applicationModal: document.getElementById("template-app-modal"),
    versionItem: document.getElementById("template-version-item"),
    versionModal: document.getElementById("template-version-modal"),
    alert: document.getElementById("template-alert")
  };

  this.ui = {
    logo: document.getElementById("logo"),
    loader: document.getElementById("loader"),
    authContainer: document.getElementById("firebaseui-auth-container"),
    applicationsCards: document.getElementById("apps-cards"),
    applicationsDetails: document.getElementById("apps-details"),
    newAppCard: document.getElementById("new-app-card"),
    userDetails: document.getElementById("user-details"),
    userEmail: document.getElementById("user-email"),
    alertContainer: document.getElementById("alert-container"),
    resetPassword: document.getElementById("user-reset-password"),
    verifyEmail: document.getElementById("user-resend-verification-email"),
    logout: document.getElementById("user-logout"),
    login: document.getElementById("user-login")
  };

  this.ids = {
    ofApplicationCard: key => `application_card_${key}`,
    ofApplicationModal: key => `application_modal_${key}`,
    ofApplicationDetails: key => `application_details_${key}`,
    ofVersionItem: key => `version_item_${key}`,
    ofVersionModal: key => `version_modal_${key}`
  };

  this.ui.logo.addEventListener("click", () => {
    window.history.pushState(null, null, "/");
    document.location.reload(true);
  });
  this.ui.newAppCard
    .querySelector(".card")
    .addEventListener("click", () => this.uiShowApplicationModal());
  this.ui.resetPassword.addEventListener("click", () =>
    this.sendPasswordResetEmail()
  );
  this.ui.verifyEmail.addEventListener("click", () =>
    this.sendEmailVerification()
  );
  this.ui.logout.addEventListener("click", () => this.auth.signOut());
  this.ui.login.addEventListener("click", () => this.uiShowLogin());
  this.ui.userEmail.addEventListener(
    "click",
    () => this.auth.currentUser && this.auth.currentUser.getIdToken(true)
  );
};

AppStore.prototype.initFirebase = function() {
  // On-Demand Firebase SDK Auto-configuration
  // https://firebase.googleblog.com/2017/04/easier-configuration-for-firebase-on-web.html
  this.database = firebase.database();
  this.databaseRefs = {};
  this.databaseRefs.store = this.database.ref("store");
  this.databaseRefs.applications = this.databaseRefs.store.child(
    "applications"
  );
  this.databaseRefs.applicationsByName = this.databaseRefs.applications.orderByChild(
    "name"
  );
  this.databaseRefs.application = key =>
    this.databaseRefs.applications.child(key);
  this.databaseRefs.versions = applicationKey =>
    this.databaseRefs.store.child("versions").child(applicationKey);
  this.databaseRefs.version = (applicationKey, versionKey) =>
    this.databaseRefs.versions(applicationKey).child(versionKey);
  this.databaseRefs.checks = this.database.ref("checks");
  this.databaseRefs.checkIsAdmin = this.databaseRefs.checks.child("admin");
  this.databaseRefs.checkIsEmailVerified = this.databaseRefs.checks.child(
    "emailVerified"
  );
  this.databaseRefs.checkIsEmailNotVerified = this.databaseRefs.checks.child(
    "emailNotVerified"
  );

  this.storage = firebase.storage();
  this.storageRefs = {};
  this.storageRefs.of = ref => {
    try {
      return ref.match(/^https?:\/\//)
        ? this.storage.refFromURL(ref)
        : this.storage.ref(ref);
    } catch (e) {}
  };
  this.storageRefs.applications = this.storage.ref("applications");
  this.storageRefs.application = key =>
    this.storageRefs.applications.child(key);
  this.storageRefs.applicationImage = key =>
    this.storageRefs.application(key).child("image");
  this.storageRefs.applicationVersionApk = (applicationKey, versionKey) =>
    this.storageRefs
      .application(applicationKey)
      .child("versions")
      .child(`${versionKey}.apk`);

  this.auth = firebase.auth();
  this.firebaseUI = new firebaseui.auth.AuthUI(this.auth);
  this.auth.onAuthStateChanged(user => this.onAuthStateChanged(user));
};

AppStore.prototype.initRouter = function() {
  window.addEventListener("popstate", event => this.routerPopstate(event));
};

AppStore.prototype.routerPopstate = function(event) {
  var state = event.state;
  if (state && state.view == "app") {
    this.routerShowApp(state.key);
  } else {
    this.routerShowRoot();
  }
};

AppStore.prototype.routerGoBack = function() {
  if (window.history.length > 1) {
    window.history.back();
  } else {
    this.routerShowRoot();
  }
};

AppStore.prototype.routerShouldDeeplink = function(key) {
  const state = window.history.state;
  return (
    state && (state.view == "root" || state.view == "app") && state.key == key
  );
};

AppStore.prototype.routerShowRoot = function(key, callback) {
  const currentState = window.history.state;
  if (currentState) {
    window.history.replaceState(currentState, null, "/");
    this.uiShowApplicationCards(key, callback);
    return;
  }
  const state = {
    view: "root",
    key: window.location.pathname.substr(1)
  };
  window.history.replaceState(state, null, state.key);
  this.uiShowApplicationCards(key, callback);
};

AppStore.prototype.routerShowApp = function(key) {
  const app = this.applications.get(key);
  if (!app) {
    this.routerShowRoot();
    return;
  }
  const state = {
    view: "app",
    key: key
  };
  if ((window.history.state || {}).view == "app") {
    window.history.replaceState(state, null, state.key);
  } else {
    window.history.pushState(state, null, state.key);
  }
  this.uiShowApplicationDetails(app.key);
};

AppStore.prototype.uiInitApplications = function() {
  this.databaseRefs.applicationsByName.off();
  this.databaseRefs.applicationsByName.on("child_added", snapshot => {
    this.uiOnApplicationAdded(snapshot);
    const key = snapshot.key;
    const app = snapshot.val();
    this.applications.set(key, app);
    this.uiAppendApplication(key, app);
    if (this.routerShouldDeeplink(key)) {
      this.routerShowApp(key);
    }
  });
  this.databaseRefs.applicationsByName.on("child_changed", snapshot => {
    this.applications.set(snapshot.key, snapshot.val());
    this.uiUpdateApplication(snapshot.key, snapshot.val());
  });
  this.databaseRefs.applicationsByName.on("child_moved", snapshot => {
    const card = document.getElementById(
      this.ids.ofApplicationCard(snapshot.key)
    );
    if (card) {
      this.uiUpdateApplicationCard(card, snapshot.key, snapshot.val());
      card.remove();
      this.uiInsertApplicationCard(card, this.ui.applicationsCards);
    }
  });
  this.databaseRefs.applicationsByName.on("child_removed", snapshot => {
    this.uiOnApplicationRemoved(snapshot);
    this.applications.delete(snapshot.key);
  });
};

AppStore.prototype.uiAppendApplication = function(key, app) {
  if (app.name == undefined || app.packageName == undefined) {
    console.error("Ignored app:", key, app);
    return;
  }
  this.uiAppendApplicationCard(key, app);
  this.uiAppendApplicationDetails(key, app);
};

AppStore.prototype.uiAppendApplicationCard = function(key, app) {
  const card = Ui.inflate(this.templates.applicationCard).firstElementChild;
  card.id = this.ids.ofApplicationCard(key);
  this.uiUpdateApplicationCard(card, key, app);
  this.uiInsertApplicationCard(card, this.ui.applicationsCards);
  card
    .querySelector(".card")
    .addEventListener("click", event => this.routerShowApp(key));
};

AppStore.prototype.uiInsertApplicationCard = function(card, root) {
  const children = root.children;
  const cardSortKey = card.getAttribute("sort-key");
  for (let i = 1; i < children.length; i++) {
    const currentNode = children[i];
    const currentSortKey = currentNode.getAttribute("sort-key");
    if (cardSortKey < currentSortKey) {
      return root.insertBefore(card, currentNode);
    }
  }
  return root.append(card);
};

AppStore.prototype.uiUpdateApplication = function(key, app) {
  const card = document.getElementById(this.ids.ofApplicationCard(key));
  this.uiUpdateApplicationCard(card, key, app);
  const details = document.getElementById(this.ids.ofApplicationDetails(key));
  this.uiUpdateApplicationDetails(details, key, app);
};

AppStore.prototype.uiUpdateApplicationCard = function(card, key, app) {
  const imageRef = this.storageRefs.of(app.image);
  if (imageRef) {
    imageRef
      .getDownloadURL()
      .then(url => (card.querySelector("[data-app-image]").src = url));
  } else {
    card.querySelector("[data-app-image]").src = app.image;
  }
  card.querySelector("[data-app-name]").textContent = app.name;
  card.setAttribute("sort-key", app.name.toLowerCase());
};

AppStore.prototype.uiAppendApplicationDetails = function(key, app) {
  const details = Ui.inflate(this.templates.applicationDetails)
    .firstElementChild;
  details.id = this.ids.ofApplicationDetails(key);
  this.uiUpdateApplicationDetails(details, key, app);
  this.ui.applicationsDetails.appendChild(details);
  details
    .querySelector("[data-app-back]")
    .addEventListener("click", event => this.routerGoBack());
  const edit = details.querySelector("[data-app-action-edit]");
  const add = details.querySelector("[data-app-action-add-version]");
  const drop = details.querySelector("[data-app-image-drop]");
  const spinner = details.querySelector("[data-app-image-drop-spinner]");
  const image = details.querySelector("[data-app-image]");

  if ((this.auth.currentUser || {}).isAdmin) {
    Ui.show(edit, add);
    edit.addEventListener("click", event => this.uiShowApplicationModal(key));
    add.addEventListener("click", event =>
      this.uiShowVersionModal(key, undefined)
    );
    drop.addEventListener("dragover", event => {
      if (
        (Utils.extractFileFromDataTransfer(event.dataTransfer),
        AppStore.CONFIG.imageMimeType)
      ) {
        event.preventDefault();
      }
    });
    drop.addEventListener("drop", event => {
      const image = Utils.extractFileFromDataTransfer(
        event.dataTransfer,
        AppStore.CONFIG.imageMimeType
      );
      if (image) {
        event.preventDefault();
        Ui.show(spinner);
        const hideSpinner = () => Ui.hide(spinner);
        this.dataUpdateApplicationImage(key, image.getAsFile())
          .then(hideSpinner)
          .catch(hideSpinner);
      }
    });
  } else {
    image.removeAttribute("drop-zone");
  }

  const versionsContainer = details.querySelector("[data-app-versions]");
  const versions = this.databaseRefs.versions(key).orderByChild("name");
  versions.off();
  versions.on("child_added", snapshot => {
    this.uiOnVersionAdded(key, snapshot);
    this.versions.set(snapshot.key, snapshot.val());
    this.uiAppendVersion(key, snapshot.key, snapshot.val(), versionsContainer);
  });
  versions.on("child_changed", snapshot => {
    this.versions.set(snapshot.key, snapshot.val());
    const item = document.getElementById(this.ids.ofVersionItem(snapshot.key));
    if (item) {
      this.uiUpdateVersion(key, snapshot.key, snapshot.val(), item);
      item.remove();
      this.uiInsertVersionItem(item, versionsContainer);
    }
  });
  versions.on("child_moved", snapshot => {
    const item = document.getElementById(this.ids.ofVersionItem(snapshot.key));
    if (item) {
      this.uiUpdateVersion(key, snapshot.key, snapshot.val(), item);
      item.remove();
      this.uiInsertVersionItem(item, versionsContainer);
    }
  });
  versions.on("child_removed", snapshot => {
    this.uiOnVersionRemoved(key, snapshot);
    this.versions.delete(snapshot.key);
  });
};

AppStore.prototype.uiAppendVersion = function(
  applicationKey,
  versionKey,
  version,
  container
) {
  const root = Ui.inflate(this.templates.versionItem).firstElementChild;
  root.id = this.ids.ofVersionItem(versionKey);
  root.setAttribute("data-version-key", versionKey);
  this.uiUpdateVersion(applicationKey, versionKey, version, root);
  root
    .querySelector("[data-version-apk]")
    .addEventListener("click", event => event.stopPropagation());
  const type = AppStore.CONFIG.apkMimeType;
  const size = AppStore.CONFIG.apkMaxSize;

  if ((this.auth.currentUser || {}).isAdmin) {
    root.addEventListener("click", event =>
      this.uiShowVersionModal(applicationKey, versionKey)
    );
    root.addEventListener("dragenter", event => {
      const result = Utils.parseDragDropEventsForFile(event, type, size);
      root.classList.add(
        result.accept ? "list-group-item-success" : "list-group-item-danger"
      );
      root.classList.add("drop-ignore-children");
    });
    root.addEventListener("dragover", event => {
      event.preventDefault();
      const result = Utils.parseDragDropEventsForFile(event, type, size);
      root.classList.add(
        result.accept ? "list-group-item-success" : "list-group-item-danger"
      );
      root.classList.add("drop-ignore-children");
    });
    root.addEventListener("dragleave", event => {
      event.preventDefault();
      if (event.target.hasAttribute("drop-zone")) {
        root.classList.remove(
          "list-group-item-success",
          "list-group-item-danger"
        );
        root.classList.remove("drop-ignore-children");
      }
    });
    root.addEventListener("drop", event => {
      event.preventDefault();
      root.classList.remove(
        "list-group-item-success",
        "list-group-item-danger"
      );
      root.classList.remove("drop-ignore-children");
      const result = Utils.parseDragDropEventsForFile(event, type, size);
      if (result.accept && result.file) {
        this.dataUpdateVersionApk(
          applicationKey,
          versionKey,
          result.file,
          true,
          root.querySelector("[data-version-progress]")
        );
      } else if (result.message) {
        this.uiTriggerAlert("alert-danger", result.message);
      }
    });
  } else {
    root.removeAttribute("drop-zone");
  }

  this.uiInsertVersionItem(root, container);
};

AppStore.prototype.uiInsertVersionItem = function(item, root) {
  const children = root.children;
  const itemVersion = new SemVer(item.getAttribute("sort-key-primary"));
  const itemTimestamp = Number(item.getAttribute("sort-key-secondary"));
  for (let i = 1; i < children.length; i++) {
    const currentNode = children[i];
    const currentVersion = new SemVer(
      currentNode.getAttribute("sort-key-primary")
    );
    const compare = SemVer.compare(itemVersion, currentVersion);
    if (compare > 0) {
      return root.insertBefore(item, currentNode);
    } else if (
      compare == 0 &&
      itemTimestamp - Number(currentNode.getAttribute("sort-key-secondary")) >=
        0
    ) {
      return root.insertBefore(item, currentNode);
    }
  }
  return root.append(item);
};

AppStore.prototype.uiUpdateVersion = function(
  applicationKey,
  versionKey,
  version,
  root
) {
  root.querySelector("[data-version-name]").textContent = version.name;
  root.setAttribute("sort-key-primary", version.name);
  root.setAttribute("sort-key-secondary", version.timestamp);
  const timestampElement = root.querySelector("[data-version-timestamp]");
  const timestamp = Number(version.timestamp);
  timestampElement.textContent = TimeAgo.valueOf(timestamp);
  timestampElement.setAttribute("title", new Date(timestamp).toLocaleString());
  timestampElement.setAttribute("timestamp", timestamp);
  const description = root.querySelector("[data-version-description]");
  description.innerHTML = version.description
    ? HtmlSanitizer.sanitize(version.description)
    : "";
  const internalLinks = description.querySelectorAll("a");
  for (let i = 0; i < internalLinks.length; i++) {
    const link = internalLinks[i];
    link.setAttribute("target", "_blank");
    link.addEventListener("click", event => event.stopPropagation());
  }
  this.uiUpdateVersionApkLink(applicationKey, versionKey, version, root);
};

AppStore.prototype.uiUpdateVersionApkLink = function(
  applicationKey,
  versionKey,
  version,
  root
) {
  const download = root.querySelector("[data-version-apk]");
  download.removeAttribute("href");
  download.removeAttribute("download");
  Ui.show(download);
  if (version.apkRef) {
    // Deferred link, fetched from storage, then applied as regular https link
    const listener = event => {
      download.removeEventListener("click", listener);
      this.storageRefs
        .of(version.apkRef)
        .getDownloadURL()
        .then(url => {
          download.setAttribute(
            "download",
            `${versionKey || version.name}.apk`
          );
          download.href = url;
          window.location = url;
        })
        .catch(error => Ui.hide(download));
    };
    download.addEventListener("click", listener);
  } else if (version.apkUrl) {
    download.href = version.apkUrl;
  } else {
    Ui.hide(download);
  }
};

AppStore.prototype.uiUpdateApplicationDetails = function(details, key, app) {
  const imageRef = this.storageRefs.of(app.image);
  if (imageRef) {
    imageRef
      .getDownloadURL()
      .then(url => (details.querySelector("[data-app-image]").src = url));
  } else {
    details.querySelector("[data-app-image]").src = app.image;
  }
  details.querySelector("[data-app-name]").textContent = app.name;
  details.querySelector("[data-app-description]").innerHTML = app.description
    ? HtmlSanitizer.sanitize(app.description)
    : "";
  const packageName = details.querySelector("[data-app-package-name]");
  if (packageName.childElementCount == 0) {
    const packageLink = Ui.inflate(this.templates.applicationLinkOutline)
      .firstElementChild;
    packageLink.querySelector("[data-app-link-icon]").innerHTML = "&#xE8C9";
    packageName.append(packageLink);
  }
  packageName.querySelector("[data-app-link-label]").textContent =
    app.packageName;
  packageName
    .querySelector("[data-app-link]")
    .setAttribute(
      "href",
      `https://play.google.com/store/apps/details?id=${app.packageName}`
    );

  const links = details.querySelector("[data-app-links]");
  Ui.empty(links);
  for (let link of [app.link_1, app.link_2, app.link_3, app.link_4]) {
    if (!link) {
      continue;
    }
    const linkElement = Ui.inflate(this.templates.applicationLinkOutline)
      .firstElementChild;
    linkElement.classList.add("mt-1", "mr-1");
    linkElement.href = link.uri;
    linkElement.textContent = link.name || link.uri.ellipsise(30, "…");
    links.append(linkElement);
  }
};

AppStore.prototype.uiShowApplicationCards = function(key, callback) {
  Ui.resetTitle();
  Ui.resetFavicon();
  Ui.show(this.ui.applicationsCards);
  $(this.ui.applicationsCards)
    .parent()
    .slideDown();
  if (key) {
    $(document.getElementById(this.ids.ofApplicationDetails(key))).slideUp(
      callback
    );
  } else {
    $(this.ui.applicationsDetails)
      .children()
      .slideUp();
  }
};

AppStore.prototype.uiShowApplicationDetails = function(key) {
  const app = this.applications.get(key);
  if (!app) {
    return;
  }

  Ui.updateTitle(app.name);
  Ui.updateFavicon(app.image);
  $(this.ui.applicationsCards)
    .parent()
    .slideUp();
  $(this.ui.applicationsDetails)
    .children()
    .slideUp();
  $("#" + this.ids.ofApplicationDetails(key)).slideDown();
};

AppStore.prototype.uiShowApplicationModal = function(key) {
  const root = Ui.inflate(this.templates.applicationModal).firstElementChild;
  root.id = this.ids.ofApplicationModal(key);
  const modal = {
    root: root,
    dialog: root.querySelector(".modal-dialog"),
    name: root.querySelector("[data-app-name]"),
    packageName: root.querySelector("[data-app-package-name]"),
    descriptionRoot: root.querySelector("[data-app-description-root]"),
    description: root.querySelector("[data-app-description]"),
    linksRoot: root.querySelector("[data-app-links-root]"),
    link1name: root.querySelector("[data-app-link-1-name]"),
    link1uri: root.querySelector("[data-app-link-1-uri]"),
    link2name: root.querySelector("[data-app-link-2-name]"),
    link2uri: root.querySelector("[data-app-link-2-uri]"),
    link3name: root.querySelector("[data-app-link-3-name]"),
    link3uri: root.querySelector("[data-app-link-3-uri]"),
    link4name: root.querySelector("[data-app-link-4-name]"),
    link4uri: root.querySelector("[data-app-link-4-uri]"),
    imageInput: root.querySelector("[data-app-image-input]"),
    imageLabel: root.querySelector("[data-app-image-label]"),
    imageMaxSize: root.querySelector("[data-app-image-max-size]"),
    progress: root.querySelector("[data-app-progress]"),
    cancel: root.querySelector("[data-app-cancel]"),
    createGroup: root.querySelector("[data-app-create-group]"),
    create: root.querySelector("[data-app-create]"),
    silent: root.querySelector("[data-app-silent]"),
    update: root.querySelector("[data-app-update]"),
    delete: root.querySelector("[data-app-delete]")
  };
  modal.imageMaxSize.textContent = Utils.formatBytesSize(
    AppStore.CONFIG.imageMaxSize
  );
  for (let link of [
    modal.link1uri,
    modal.link2uri,
    modal.link3uri,
    modal.link4uri
  ]) {
    link.addEventListener("input", event =>
      Utils.validateUriInput(event.target, true)
    );
  }
  modal.packageName.addEventListener("input", event =>
    event.target.setCustomValidity(
      event.target.value.match(AppStore.CONSTANTS.packageNameRegex)
        ? ""
        : "error"
    )
  );
  const app = this.applications.get(key);
  if (app) {
    modal.name.value = app.name;
    modal.packageName.value = app.packageName;
    modal.description.innerHTML = app.description
      ? HtmlSanitizer.sanitize(app.description)
      : "";
    modal.link1name.value = app.link_1 ? app.link_1.name : null;
    modal.link1uri.value = app.link_1 ? app.link_1.uri : null;
    modal.link2name.value = app.link_2 ? app.link_2.name : null;
    modal.link2uri.value = app.link_2 ? app.link_2.uri : null;
    modal.link3name.value = app.link_3 ? app.link_3.name : null;
    modal.link3uri.value = app.link_3 ? app.link_3.uri : null;
    modal.link4name.value = app.link_4 ? app.link_4.name : null;
    modal.link4uri.value = app.link_4 ? app.link_4.uri : null;
    modal.imageInput.removeAttribute("required");
    modal.imageLabel.textContent = "Unchanged";
    modal.createGroup.remove();
    modal.delete.addEventListener("click", event =>
      this.uiRemoveApplication(modal, key)
    );
    modal.update.addEventListener("click", event =>
      this.uiApplicationModalCommit(modal, key, app)
    );
  } else {
    modal.dialog.classList.add("modal-sm");
    modal.descriptionRoot.remove();
    modal.linksRoot.remove();
    modal.delete.remove();
    modal.update.remove();
    modal.create.addEventListener("click", event =>
      this.uiApplicationModalCommit(modal, key, app)
    );
    modal.silent.addEventListener("click", event => {
      const btn = modal.silent;
      const icon = modal.silent.firstElementChild;
      if (btn.hasAttribute("data-app-silent-flag")) {
        btn.removeAttribute("data-app-silent-flag");
        icon.innerHTML = "&#xE7F4;";
      } else {
        btn.setAttribute("data-app-silent-flag", "true");
        icon.innerHTML = "&#xE7F6;";
      }
      btn.blur();
    });
  }
  modal.imageInput.addEventListener("change", event => {
    const file = event.target.files[0];
    if (file) {
      modal.imageLabel.textContent = `${Utils.formatBytesSize(
        file.size,
        2
      )} • ${file.name}`;
      Utils.validateFileInput(
        modal.imageInput,
        file,
        AppStore.CONFIG.imageMimeType,
        AppStore.CONFIG.imageMaxSize
      );
    } else {
      modal.imageLabel.textContent = app ? "Unchanged" : null;
    }
  });
  $(modal.root)
    .on("hidden.bs.modal", event => {
      modal.root.remove();
    })
    .modal("show");
};

AppStore.prototype.uiApplicationModalCommit = function(modal, key, app) {
  const data = this.uiExtractDataFromApplicationModal(modal, key);
  if (!this.uiValidateApplicationModal(modal, key, app)) {
    return;
  }
  const inputs = modal.root.querySelectorAll("input, textarea, button");
  Ui.disable(...inputs);
  modal.imageLabel.setAttribute("readonly", "");
  Ui.show(modal.progress);
  Ui.hide(modal.cancel, modal.delete);
  const createOrUpdate = key
    ? this.dataUpdateApplication(key, data)
    : this.dataPushNewApplication(data);
  createOrUpdate.then(data => $(modal.root).modal("hide"));
};

AppStore.prototype.uiExtractDataFromApplicationModal = function(modal, key) {
  const data = {};
  data.name = modal.name.value;
  data.packageName = modal.packageName.value;
  const files = modal.imageInput.files;
  if (files && files.length > 0) {
    data.image = files[0];
  }
  if (key) {
    data.description = modal.description.value;
    if (modal.link1uri.value) {
      data.link_1 = {
        name: modal.link1name.value,
        uri: modal.link1uri.value
      };
    }
    if (modal.link2uri.value) {
      data.link_2 = {
        name: modal.link2name.value,
        uri: modal.link2uri.value
      };
    }
    if (modal.link3uri.value) {
      data.link_3 = {
        name: modal.link3name.value,
        uri: modal.link3uri.value
      };
    }
    if (modal.link4uri.value) {
      data.link_4 = {
        name: modal.link4name.value,
        uri: modal.link4uri.value
      };
    }
  }
  if (modal.silent.hasAttribute("data-app-silent-flag")) {
    data.silent = true;
  }
  return data;
};

AppStore.prototype.uiValidateApplicationModal = function(modal, key, app) {
  const data = this.uiExtractDataFromApplicationModal(modal, app);
  if (!data.name) {
    modal.name.focus();
    return false;
  }
  if (
    !data.packageName ||
    !data.packageName.match(AppStore.CONSTANTS.packageNameRegex)
  ) {
    console.error("Invalid packageName");
    modal.packageName.focus();
    return false;
  }
  if (!app && !data.image) {
    console.error("Image is required");
    modal.imageInput.focus();
    return false;
  }
  if (data.image) {
    if (
      !Utils.validateFileInput(
        modal.imageInput,
        data.image,
        AppStore.CONFIG.imageMimeType,
        AppStore.CONFIG.imageMaxSize
      )
    ) {
      return false;
    }
  }
  if (data.link_1 && !Utils.isUriValid(data.link_1.uri)) {
    console.error("Link is invalid");
    modal.link1uri.focus();
    return false;
  }
  if (data.link_2 && !Utils.isUriValid(data.link_2.uri)) {
    console.error("Link is invalid");
    modal.link2uri.focus();
    return false;
  }
  if (data.link_3 && !Utils.isUriValid(data.link_3.uri)) {
    console.error("Link is invalid");
    modal.link3uri.focus();
    return false;
  }
  if (data.link_4 && !Utils.isUriValid(data.link_4.uri)) {
    console.error("Link is invalid");
    modal.link4uri.focus();
    return false;
  }
  return data;
};

AppStore.prototype.uiShowVersionModal = function(applicationKey, versionKey) {
  const root = Ui.inflate(this.templates.versionModal).firstElementChild;
  root.id = this.ids.ofVersionModal(versionKey);
  root.setAttribute("data-version-app-key", applicationKey);
  const modal = {
    root: root,
    name: root.querySelector("[data-version-name]"),
    description: root.querySelector("[data-version-description]"),
    descriptionPreview: root.querySelector(
      "[data-version-description-preview]"
    ),
    descriptionPreviewToggle: root.querySelector(
      "[data-version-description-preview-toggle]"
    ),
    timestamp: root.querySelector("[data-version-timestamp]"),
    datetime: root.querySelector("[data-version-datetime]"),
    timestampLabel: root.querySelector("[data-version-timestamp-label]"),
    now: root.querySelector("[data-version-timestamp-now]"),
    apkInput: root.querySelector("[data-version-apk-input]"),
    apkLabel: root.querySelector("[data-version-apk-label]"),
    apkUrl: root.querySelector("[data-version-apk-url]"),
    apkToggle: root.querySelector("[data-version-apk-toggle]"),
    apkMaxSize: root.querySelector("[data-version-apk-max-size]"),
    progress: root.querySelector("[data-version-progress]"),
    cancel: root.querySelector("[data-version-cancel]"),
    createGroup: root.querySelector("[data-version-create-group]"),
    create: root.querySelector("[data-version-create]"),
    silent: root.querySelector("[data-version-silent]"),
    update: root.querySelector("[data-version-update]"),
    delete: root.querySelector("[data-version-delete]")
  };
  modal.apkMaxSize.textContent = Utils.formatBytesSize(
    AppStore.CONFIG.apkMaxSize
  );
  modal.name.addEventListener("input", event =>
    event.target.setCustomValidity(
      event.target.value.match(SemVer.REGEX) ? "" : "error"
    )
  );
  modal.apkUrl.addEventListener("input", event =>
    Utils.validateUriInput(event.target)
  );
  modal.descriptionPreviewToggle.addEventListener("click", event => {
    const showPreview = Ui.isHidden(modal.descriptionPreview);
    if (showPreview) {
      modal.descriptionPreviewToggle.innerHTML = "&#xE8F5";
      Ui.hide(modal.description);
      modal.descriptionPreview.innerHTML = HtmlSanitizer.sanitize(
        modal.description.value || "&nbsp;"
      );
      Ui.show(modal.descriptionPreview);
    } else {
      modal.descriptionPreviewToggle.innerHTML = "&#xE8F4;";
      Ui.show(modal.description);
      Ui.hide(modal.descriptionPreview);
      modal.descriptionPreview.innerHTML = null;
    }
  });

  const apkToggle = event => {
    const displayUrl = Ui.isHidden(modal.apkUrl);
    if (displayUrl) {
      Ui.hide(modal.apkInput.parentElement.parentElement);
      Ui.show(modal.apkUrl);
    } else {
      Ui.show(modal.apkInput.parentElement.parentElement);
      Ui.hide(modal.apkUrl);
    }
  };
  modal.apkToggle.addEventListener("click", apkToggle);

  const updateTimestamp = timestampInMillis => {
    modal.timestamp.value = Math.trunc(timestampInMillis / 1000);
    modal.datetime.value = new Date(timestampInMillis).toString();
    modal.timestamp.dispatchEvent(new Event("input"));
  };
  modal.timestamp.addEventListener("input", event => {
    const timestampInMillis = Number(modal.timestamp.value) * 1000;
    const date = new Date(timestampInMillis);
    modal.datetime.value = date.toString();
    modal.timestampLabel.textContent = TimeAgo.valueOf(timestampInMillis);
    modal.timestampLabel.setAttribute("title", date.toLocaleString());
  });
  modal.datetime.addEventListener("input", event => {
    const date = new Date(modal.datetime.value);
    const timeInMillis = date.getTime();
    if (!isNaN(timeInMillis)) {
      updateTimestamp(timeInMillis);
    }
  });
  modal.now.addEventListener("click", event => {
    if (Ui.isDisabled(modal.now.parentElement)) {
      return;
    }
    updateTimestamp(Date.now());
  });
  const version = this.versions.get(versionKey);
  if (version) {
    modal.name.value = version.name;
    modal.description.innerHTML = version.description
      ? HtmlSanitizer.sanitize(version.description)
      : "";
    updateTimestamp(version.timestamp);

    if (version.apkRef) {
      modal.apkInput.removeAttribute("required");
      modal.apkLabel.textContent = "Unchanged";
    } else if (version.apkUrl) {
      modal.apkUrl.value = version.apkUrl;
      modal.apkToggle.click();
    }
    modal.delete.addEventListener("click", event =>
      this.uiRemoveVersion(modal, applicationKey, versionKey)
    );
    modal.createGroup.remove();
    modal.update.addEventListener("click", event =>
      this.uiVersionModalCommit(modal, applicationKey, versionKey, version)
    );
  } else {
    updateTimestamp(Date.now());
    modal.delete.remove();
    modal.update.remove();
    modal.create.addEventListener("click", event =>
      this.uiVersionModalCommit(modal, applicationKey, undefined, undefined)
    );
    modal.silent.addEventListener("click", event => {
      const btn = modal.silent;
      const icon = modal.silent.firstElementChild;
      if (btn.hasAttribute("data-version-silent-flag")) {
        btn.removeAttribute("data-version-silent-flag");
        icon.innerHTML = "&#xE7F4;";
      } else {
        btn.setAttribute("data-version-silent-flag", "true");
        icon.innerHTML = "&#xE7F6;";
      }
      btn.blur();
    });
  }
  modal.apkInput.addEventListener("change", event => {
    const file = event.target.files[0];
    if (file) {
      modal.apkLabel.textContent = `${Utils.formatBytesSize(file.size, 2)} • ${
        file.name
      }`;
      Utils.validateFileInput(
        modal.apkInput,
        file,
        AppStore.CONFIG.apkMimeType,
        AppStore.CONFIG.apkMaxSize
      );
    } else {
      modal.apkLabel.textContent = version ? "Unchanged" : null;
    }
  });
  $(modal.root)
    .on("hidden.bs.modal", event => {
      modal.root.remove();
    })
    .modal("show");
};

AppStore.prototype.uiVersionModalCommit = function(
  modal,
  applicationKey,
  versionKey,
  version
) {
  const data = this.uiVersionModalExtract(modal, version);
  if (!this.uiVersionModalValidate(modal, versionKey, data)) {
    return;
  }
  // disable modal elements
  const inputs = modal.root.querySelectorAll("input, textarea, button");
  Ui.disable(...inputs, modal.now);
  modal.apkLabel.setAttribute("readonly", "");
  modal.progress.firstElementChild.style.width = data.apk ? "0%" : "100%";
  Ui.show(modal.progress);
  Ui.hide(modal.cancel, modal.delete);
  const createOrUpdate = versionKey
    ? this.dataUpdateVersion(applicationKey, versionKey, data, modal.progress)
    : this.dataPushNewVersion(applicationKey, data, modal.progress);
  createOrUpdate
    .catch(error => {
      console.error(error);
      this.uiTriggerAlert("alert-danger", "Error…");
    })
    .then(data => $(modal.root).modal("hide"));
};

AppStore.prototype.uiVersionModalExtract = function(modal, version) {
  const data = {};
  data.name = modal.name.value;
  data.timestamp = Number(modal.timestamp.value) * 1000;
  data.description = modal.description.value;
  const files = modal.apkInput.files;
  if (files && files.length > 0) {
    data.apk = files[0];
  }
  data.apkUrl = modal.apkUrl.value;
  if (modal.silent.hasAttribute("data-version-silent-flag")) {
    data.silent = true;
  }
  return data;
};

AppStore.prototype.uiVersionModalValidate = function(modal, version, data) {
  if (!data.name || !data.name.match(SemVer.REGEX)) {
    console.error("Invalid name");
    modal.name.focus();
    return false;
  }
  if (!data.timestamp) {
    console.error("Timestamp is required");
    modal.packageName.focus();
    return false;
  }

  const apkAsFile = Ui.isHidden(modal.apkUrl);
  if (apkAsFile) {
    if (!version) {
      if (!data.apk) {
        console.error("APK is required");
        modal.apkInput.focus();
        return false;
      }
    }
    if (data.apk) {
      if (
        !Utils.validateFileInput(
          modal.apkInput,
          data.apk,
          AppStore.CONFIG.apkMimeType,
          AppStore.MAX_SIZE_APK
        )
      ) {
        return false;
      }
    }
  } else {
    if (!Utils.isUriValid(data.apkUrl)) {
      console.error("APK URL is required");
      modal.apkUrl.focus();
      return false;
    }
  }
  return data;
};

AppStore.prototype.uiOnApplicationAdded = function(snapshot) {
  // Show alert only if it's a new/unknown application
  if (!this.applications.get(snapshot.key)) {
    this.uiTriggerAlert(
      "alert-success",
      `Application <b>${snapshot.val().name}</b> created`
    );
  }
};

/**
 * Hide all UI elements related to this application and restore the application grid if necessary
 */
AppStore.prototype.uiOnApplicationRemoved = function(snapshot) {
  const key = snapshot.key;
  const app = snapshot.val();
  // Version modals (new/edit) of this application
  const versionModals = document.querySelectorAll(
    `[data-version-app-key="${key}"]`
  );
  for (let i = 0; i < versionModals.length; i++) {
    $(versionModals[i]).modal("hide");
  }
  // Modal (edit) of this application
  const modal = document.getElementById(this.ids.ofApplicationModal(key));
  if (modal) {
    $(modal).modal("hide");
  }
  // Application details
  const details = document.getElementById(this.ids.ofApplicationDetails(key));
  if (details) {
    // restore the application list if details are currently on screen
    if (!Ui.isHidden(details)) {
      this.routerShowRoot(key, () => details.remove());
    } else {
      details.remove();
    }
  }
  // Application card
  const card = document.getElementById(this.ids.ofApplicationCard(key));
  if (card) {
    card.remove();
  }

  if (this.applications.get(snapshot.key)) {
    this.uiTriggerAlert(
      "alert-danger",
      `Application <b>${snapshot.val().name}</b> removed`
    );
  }
};

AppStore.prototype.uiOnVersionAdded = function(applicationKey, snapshot) {
  const application = this.applications.get(applicationKey);
  const version = this.versions.get(snapshot.key);
  if (!version && application) {
    this.uiTriggerAlert(
      "alert-success",
      `<b>${application.name}</b><br>Version <b>${
        snapshot.val().name
      }</b> created`
    );
  }
};

/**
 * Remove all UI elements related to this version
 */
AppStore.prototype.uiOnVersionRemoved = function(applicationKey, snapshot) {
  const key = snapshot.key;
  // List item
  const item = document.getElementById(this.ids.ofVersionItem(key));
  if (item) {
    item.remove();
  }
  // Edit modal
  const modal = document.getElementById(this.ids.ofVersionModal(key));
  if (modal) {
    $(modal).modal("hide");
  }

  const application = this.applications.get(applicationKey);
  const version = this.versions.get(snapshot.key);
  if (version && application) {
    this.uiTriggerAlert(
      "alert-danger",
      `<b>${application.name}</b><br>Version <b>${
        snapshot.val().name
      }</b> removed`
    );
  }
};

AppStore.prototype.dataPushNewApplication = function(app) {
  const data = {
    name: app.name,
    packageName: app.packageName,
    description: ""
  };
  if (app.silent === true) {
    data.silent = true;
  }
  const updateImage = snapshot =>
    this.dataUpdateApplicationImage(snapshot.key, app.image);
  console.log("Push application", data);
  return this.databaseRefs.applications.push(data).then(updateImage);
};

AppStore.prototype.dataUpdateApplicationImage = function(key, file) {
  if (!file || !file.type.match(AppStore.CONFIG.imageMimeType)) {
    console.error("Image is required");
    return false;
  }
  if (file.size > AppStore.CONFIG.imageMaxSize) {
    console.error(
      `Invalid file size [${Utils.formatBytesSize(
        file.size,
        2
      )}], expected [${Utils.formatBytesSize(AppStore.CONFIG.imageMaxSize, 2)}]`
    );
    return false;
  }
  return this.storageRefs
    .applicationImage(key)
    .put(file)
    .then(snapshot => snapshot.ref.getDownloadURL())
    .then(url => this.databaseRefs.application(key).update({ image: url }));
};

AppStore.prototype.dataUpdateApplication = function(key, data) {
  const update = {
    name: data.name,
    packageName: data.packageName,
    description: HtmlSanitizer.sanitize(data.description),
    link_1: data.link_1
      ? {
          name: data.link_1.name,
          uri: data.link_1.uri
        }
      : null,
    link_2: data.link_2
      ? {
          name: data.link_2.name,
          uri: data.link_2.uri
        }
      : null,
    link_3: data.link_3
      ? {
          name: data.link_3.name,
          uri: data.link_3.uri
        }
      : null,
    link_4: data.link_4
      ? {
          name: data.link_4.name,
          uri: data.link_4.uri
        }
      : null
  };
  const updateImageIfNeeded = () =>
    data.image ? this.dataUpdateApplicationImage(key, data.image) : null;
  return this.databaseRefs
    .application(key)
    .update(update)
    .then(updateImageIfNeeded);
};

AppStore.prototype.uiRemoveApplication = function(modal, key) {
  if (!confirm("Delete this application?")) {
    return;
  }

  const inputs = modal.root.querySelectorAll("input, textarea, button");
  Ui.disable(...inputs, modal.create, modal.update, modal.cancel, modal.delete);
  modal.imageLabel.setAttribute("readonly", "");

  this.dataRemoveApplication(key).then(
    () => (modal ? $(modal.root).modal("hide") : null)
  );
};

AppStore.prototype.dataRemoveApplication = function(key) {
  const deleteApplicationImage = () => {
    const ref = this.storageRefs.applicationImage(key);
    console.log("Remove application image", ref.toString());
    return ref.delete();
  };
  const removeApplication = () => {
    const ref = this.databaseRefs.application(key);
    console.log("Remove application", ref.toString());
    return ref.remove();
  };
  const removeVersions = snapshot => {
    const promises = [];
    snapshot.forEach(versionSnapshot => {
      promises.push(this.dataRemoveVersion(key, versionSnapshot.key));
    });
    return Promise.all(promises);
  };

  return removeApplication()
    .then(deleteApplicationImage, error => console.error(error))
    .then(
      this.databaseRefs
        .versions(key)
        .once("value")
        .then(removeVersions)
    );
};

AppStore.prototype.dataPushNewVersion = function(
  applicationKey,
  version,
  progressBar
) {
  const data = {
    name: version.name,
    timestamp: Number(version.timestamp),
    description: HtmlSanitizer.sanitize(version.description),
    apkUrl: version.apkUrl || null
  };
  if (version.silent === true) {
    data.silent = true;
  }
  const updateApkIfNeeded = snapshot =>
    version.apk
      ? this.dataUpdateVersionApk(
          applicationKey,
          snapshot.key,
          version.apk,
          false,
          progressBar
        )
      : null;

  console.log("Push version", data);
  return this.databaseRefs
    .versions(applicationKey)
    .push(data)
    .then(updateApkIfNeeded);
};

AppStore.prototype.dataUpdateVersion = function(
  applicationKey,
  versionKey,
  data,
  progressBar
) {
  const currentVersion = this.versions.get(versionKey);
  const currentApkRef = currentVersion ? currentVersion.apkRef : null;

  const update = {
    name: data.name,
    timestamp: Number(data.timestamp),
    description: HtmlSanitizer.sanitize(data.description)
  };

  if (data.apkUrl) {
    update.apkRef = null;
    update.apkUrl = data.apkUrl;
  }
  const updateOrRemoveApk = snapshot => {
    if (data.apk) {
      return this.dataUpdateVersionApk(
        applicationKey,
        versionKey,
        data.apk,
        false,
        progressBar
      );
    } else if (data.apkUrl && currentApkRef) {
      const ref = this.storageRefs.of(currentApkRef);
      console.log("Delete apk", ref.toString());
      return ref.delete();
    }
  };

  const ref = this.databaseRefs.version(applicationKey, versionKey);
  console.log("Update version", ref.toString(), update);
  return ref.update(update).then(updateOrRemoveApk);
};

AppStore.prototype.dataUpdateVersionApk = function(
  applicationKey,
  versionKey,
  file,
  updateTimestamp,
  progressBar
) {
  const updateVersion = snapshot => {
    const update = {
      apkRef: snapshot.metadata.fullPath,
      apkGeneration: Number(snapshot.metadata.generation),
      apkUrl: null
    };
    if (updateTimestamp) {
      update.timestamp = Date.now();
    }
    const ref = this.databaseRefs.version(applicationKey, versionKey);
    console.log("Update version", ref.toString(), update);
    return ref.update(update);
  };
  const ref = this.storageRefs.applicationVersionApk(
    applicationKey,
    versionKey
  );
  console.log("Push apk file", ref.toString(), file);
  const uploadTask = ref.put(file);
  if (progressBar) {
    const bar = progressBar.firstElementChild;
    bar.style.width = "0%";
    Ui.show(progressBar);
    uploadTask.on(
      firebase.storage.TaskEvent.STATE_CHANGED,
      snapshot =>
        (bar.style.width =
          Math.round(snapshot.bytesTransferred / snapshot.totalBytes * 100) +
          "%"),
      error => Ui.hide(progressBar),
      complete => Ui.hide(progressBar)
    );
  }
  return uploadTask.then(updateVersion);
};

AppStore.prototype.uiRemoveVersion = function(
  modal,
  applicationKey,
  versionKey
) {
  if (!confirm("Delete this version?")) {
    return;
  }

  // disable modal elements
  const inputs = modal.root.querySelectorAll("input, textarea, button");
  Ui.disable(
    ...inputs,
    modal.now,
    modal.delete,
    modal.cancel,
    modal.update,
    modal.create
  );
  modal.apkLabel.setAttribute("readonly", "");

  this.dataRemoveVersion(applicationKey, versionKey).then(() =>
    $(modal.root).modal("hide")
  );
};

AppStore.prototype.dataRemoveVersion = function(applicationKey, versionKey) {
  const version = this.versions.get(versionKey);
  if (!version) {
    return;
  }
  const deleteApkIfNeeded = () => {
    if (version.apkRef) {
      const ref = this.storageRefs.of(version.apkRef);
      console.log("Delete apk", ref.toString());
      return ref.delete();
    } else {
      return Promise.resolve();
    }
  };
  const removeVersion = () => {
    const ref = this.databaseRefs.version(applicationKey, versionKey);
    console.log("Remove version", ref.toString());
    return ref.remove();
  };
  return deleteApkIfNeeded().then(removeVersion);
};

AppStore.prototype.uiTriggerAlert = function(
  type,
  content,
  durationInSeconds = 10
) {
  const alert = Ui.inflate(this.templates.alert).firstElementChild;
  alert.classList.add(type);
  alert.querySelector(
    "[data-alert-message]"
  ).innerHTML = HtmlSanitizer.sanitize(content);
  this.ui.alertContainer.prepend(alert);
  window.setTimeout(() => {
    alert.classList.add("show");
  }, 10);

  if (durationInSeconds == Infinity) {
    return;
  }
  window.setTimeout(() => $(alert).alert("close"), durationInSeconds * 1000);
};

AppStore.prototype.onAuthStateChanged = function(user) {
  console.log("onAuthStateChanged", user);
  this.uiUpdateUserNavigationMenu(user);
  if (user) {
    this.onUserLoggedIn(user);
  } else {
    this.onUserLoggedOut();
  }
};

AppStore.prototype.uiUpdateUserNavigationMenu = function(user) {
  Ui.show(this.ui.userDetails);
  Ui.enable(this.ui.userDetails);
  this.ui.userDetails.classList.remove("disabled");

  if (user) {
    Ui.hide(this.ui.login);
    this.ui.userEmail.textContent = user.email;
    if (user.emailVerified) {
      this.ui.userDetails.classList.remove("text-warning");
      Ui.hide(this.ui.verifyEmail);
    } else {
      this.ui.userDetails.classList.add("text-warning");
      Ui.show(this.ui.verifyEmail);
    }
    Ui.show(this.ui.resetPassword, this.ui.logout);
  } else {
    Ui.show(this.ui.login);
    this.ui.userEmail.textContent = "Anonymous";
    Ui.hide(this.ui.resetPassword, this.ui.logout, this.ui.verifyEmail);
    this.ui.userDetails.classList.remove("text-warning");
  }
};

AppStore.prototype.onUserLoggedIn = function(user) {
  const ensureUserIsAllowed = user => {
    if (user.isAnonymous && !AppStore.CONFIG.allowAnonymousUsers) {
      user.isAllowed = false;
      /*user.delete().catch(error => {});*/
      this.auth.signOut();
      this.uiTriggerAlert(
        "alert-danger",
        `<b>${AppStore.CONSTANTS.shrug}</b><br>Anonymous users not allowed!`
      );
      return Promise.reject(new Error("Anonymous user"));
    }
    if (!user.emailVerified && !AppStore.CONFIG.allowUnverifiedEmail) {
      this.uiTriggerAlert(
        "alert-warning",
        `Email not verified yet.<br>Click on ${
          this.ui.userDetails.innerHTML
        } and <b>${this.ui.verifyEmail.textContent}</b>.`,
        Infinity
      );
      Ui.hide(this.ui.authContainer, this.ui.loader);
      return Promise.reject(new Error("Email not verified"));
    }

    // force refresh IdToken to trigger remote 'email_verified'
    return user
      .getIdToken(true)
      .catch(error => console.error(error))
      .then(token => this.databaseRefs.store.once("value"))
      .then(snapshot => {
        user.isAllowed = true;
        this.updateInternalData(snapshot);
        return Promise.resolve(user);
      })
      .catch(error => {
        console.error(error);
        user.isAllowed = false;

        // Prevent developer error: anonymous users
        if (user.isAnonymous && AppStore.CONFIG.allowAnonymousUsers) {
          AppStore.CONFIG.allowAnonymousUsers = false;
          this.auth.signOut();
          return Promise.reject(new Error("Not allowed"));
        }
        // Prevent developer error: unverified emails
        if (!user.emailVerified && AppStore.CONFIG.allowUnverifiedEmail) {
          this.uiTriggerAlert(
            "alert-warning",
            `Email not verified yet.<br>Click on ${
              this.ui.userDetails.innerHTML
            } and <b>${this.ui.verifyEmail.textContent}</b>.`,
            Infinity
          );
          Ui.hide(this.ui.authContainer, this.ui.loader);
          return Promise.reject(new Error("Email not verified"));
        }

        /*user.delete().catch(error => {});*/
        this.auth.signOut();
        this.uiTriggerAlert(
          "alert-danger",
          `<b>${AppStore.CONSTANTS.shrug}</b><br>You are not allowed here!`
        );
        return Promise.reject(new Error("Not allowed"));
      });
  };

  const checkAdminAccess = user => {
    return this.databaseRefs.checkIsAdmin
      .once("value")
      .then(snapshot => {
        user.isAdmin = true;
        Ui.show(this.ui.newAppCard);
      })
      .catch(error => {
        user.isAdmin = false;
        Ui.hide(this.ui.newAppCard);
      })
      .then(() => Promise.resolve(user));
  };

  const renderUserInterface = user => {
    Ui.hide(this.ui.authContainer, this.ui.loader);
    this.routerShowRoot();
    this.uiInitApplications();
    return Promise.resolve(user);
  };

  Promise.resolve(user)
    .then(ensureUserIsAllowed)
    .then(checkAdminAccess)
    .then(renderUserInterface)
    .catch(error => console.error(error));
};

AppStore.prototype.onUserLoggedOut = function() {
  this.updateInternalData();
  this.databaseRefs.applicationsByName.off();

  const fallback = () => {
    this.databaseRefs.store
      .once("value")
      .then(snapshot => {
        this.updateInternalData(snapshot);
        Ui.hide(this.ui.authContainer, this.ui.loader);
        this.routerShowRoot();
        this.uiInitApplications();
      })
      .catch(error => this.uiShowLogin());
  };
  if (AppStore.CONFIG.allowAnonymousUsers) {
    this.auth.signInAnonymously().catch(function(error) {
      console.error("Unable to signInAnonymously", error.code, error.message);
      fallback();
    });
  } else {
    fallback();
  }
};

AppStore.prototype.uiShowLogin = function() {
  Ui.show(this.ui.authContainer);
  AppStore.CONFIG.firebaseUi.callbacks = {
    signInSuccessWithAuthResult: (authResult, redirectUrl) => {
      Ui.show(this.ui.loader);
      return false;
    },
    uiShown: () => Ui.hide(this.ui.loader)
  };
  this.firebaseUI.start(
    "#firebaseui-auth-container",
    AppStore.CONFIG.firebaseUi
  );
};

AppStore.prototype.sendEmailVerification = function() {
  const user = this.auth.currentUser;
  if (user.emailVerified) {
    return;
  }
  const success = () =>
    this.uiTriggerAlert(
      "alert-success",
      `Verification email sent to <b>${user.email}</b>.`
    );
  user.sendEmailVerification().then(success);
};

AppStore.prototype.sendPasswordResetEmail = function() {
  const success = () =>
    this.uiTriggerAlert("alert-success", "Password reset email sent.");
  this.auth.sendPasswordResetEmail(this.auth.currentUser.email).then(success);
};

window.onload = function() {
  window.store = new AppStore();
};

/* Utils */

function Ui() {}

Ui.inflate = function(template) {
  return document.importNode(template.content, true);
};

Ui.show = function(...elements) {
  for (let e of elements) {
    e && e.removeAttribute("hidden");
  }
};

Ui.hide = function(...elements) {
  for (let e of elements) {
    e && e.setAttribute("hidden", "true");
  }
};

Ui.isHidden = function(element) {
  return element && element.hasAttribute("hidden");
};

Ui.enable = function(...elements) {
  for (let e of elements) {
    e && e.removeAttribute("disabled");
  }
};

Ui.disable = function(...elements) {
  for (let e of elements) {
    e && e.setAttribute("disabled", "true");
  }
};

Ui.isDisabled = function(element) {
  return element && element.hasAttribute("disabled");
};

Ui.empty = function(...elements) {
  for (let e of elements) {
    while (e && e.firstChild) {
      e.removeChild(e.firstChild);
    }
  }
};

Ui.title = function() {
  return document.querySelector("head > title");
};

Ui.updateTitle = function(string) {
  const title = Ui.title();
  if (!title.hasAttribute("value")) {
    title.setAttribute("value", title.textContent);
  }
  title.textContent = string;
};

Ui.resetTitle = function() {
  const title = Ui.title();
  if (title.hasAttribute("value")) {
    title.textContent = title.getAttribute("value");
  }
};

Ui.favicon = function(src) {
  return document.querySelectorAll("link[rel=icon]");
};

Ui.updateFavicon = function(src) {
  for (let favicon of Ui.favicon()) {
    if (!favicon.hasAttribute("_href")) {
      favicon.setAttribute("_href", favicon.getAttribute("href"));
    }
    favicon.setAttribute("href", src);
  }
};

Ui.resetFavicon = function() {
  for (let favicon of Ui.favicon()) {
    if (favicon.hasAttribute("_href")) {
      favicon.setAttribute("href", favicon.getAttribute("_href"));
    }
  }
};

function Utils() {}

Utils.preventDefaultDrop = function() {
  const preventDefaultBehavior = e => {
    if (e.target.tagName != "INPUT" && !e.target.hasAttribute("drop-zone")) {
      e.preventDefault();
      e.dataTransfer.effectAllowed = "none";
      e.dataTransfer.dropEffect = "none";
    }
  };
  window.addEventListener("dragenter", preventDefaultBehavior, false);
  window.addEventListener("dragover", preventDefaultBehavior);
  window.addEventListener("drop", preventDefaultBehavior);
};

Utils.disableDragAndDropEvent = function(event) {
  event.dataTransfer.effectAllowed = "none";
  event.dataTransfer.dropEffect = "none";
  event.preventDefault();
};

Utils.validateUriInput = function(input, allowEmpty = false) {
  const value = input.value;
  input.setCustomValidity(Utils.isUriValid(value, allowEmpty) ? "" : "error");
};

Utils.isUriValid = function(string, allowEmpty = false) {
  if (!string && allowEmpty) {
    return true;
  }
  const parser = document.createElement("a");
  parser.href = string;
  return string.startsWith(parser.protocol);
};

Utils.validateFileInput = function(input, file, type, size) {
  if (!file) {
    const msg = "No file";
    console.error(msg);
    input.setCustomValidity(msg);
    input.focus();
    return false;
  }
  if (!file.type.match(type)) {
    const msg = `Invalid file type [${file.type}], expected [${type}]`;
    console.error(msg);
    input.setCustomValidity(msg);
    input.focus();
    return false;
  }
  if (file.size > size) {
    const msg = `Invalid file size [${Utils.formatBytesSize(
      file.size,
      2
    )}], expected [${Utils.formatBytesSize(size, 2)}]`;
    console.error(msg);
    input.focus();
    input.setCustomValidity(msg);
    return false;
  }
  input.setCustomValidity("");
  return true;
};

Utils.extractFileFromDataTransfer = function(dataTransfer, type) {
  const types = dataTransfer.types;
  const items = dataTransfer.items;
  if (
    types.length == 1 &&
    types[0] == "Files" &&
    items.length == 1 &&
    items[0].type.match(type)
  ) {
    return items[0];
  }
};

Utils.parseDragDropEventsForFile = function(event, type, size) {
  const result = {
    accept: true
  };
  const types = event.dataTransfer.types;
  const items = event.dataTransfer.items;
  if (
    types.length == 1 &&
    types[0] == "Files" &&
    items.length == 1 &&
    items[0].type.match(type)
  ) {
    const file = items[0].getAsFile();
    result.file = file;
    // When event is not 'drop', getAsFile() will return null
    if (!file) {
      return result;
    }
    if (!file.type.match(type)) {
      result.message = `Expected file type: <b>${type}</b>, but file type was <b>${
        file.type
      }</b>`;
      result.accept = false;
      Utils.disableDragAndDropEvent(event);
      return result;
    }
    if (file.size > size) {
      result.message = `File size is too large: <b>${Utils.formatBytesSize(
        file.size,
        2
      )}</b> (max: <b>${Utils.formatBytesSize(size, 2)}</b>)`;
      result.accept = false;
      Utils.disableDragAndDropEvent(event);
      return result;
    }
    // success
    return result;
  }
  Utils.disableDragAndDropEvent(event);
  result.accept = false;
  return result;
};

Utils.formatBytesSize = function(bytes, decimalPoint) {
  if (bytes == 0) return "0 Byte";
  const k = 1024,
    dm = decimalPoint || 0,
    sizes = ["Bytes", "KB", "MB", "GB", "TB", "PB", "EB", "ZB", "YB"],
    i = Math.floor(Math.log(bytes) / Math.log(k));
  const value = parseFloat((bytes / Math.pow(k, i)).toFixed(dm));
  return `${value} ${sizes[i]}`;
};

Utils.initTimestampTimer = function() {
  setInterval(() => {
    const elements = document.querySelectorAll("[auto-timestamp][timestamp]");
    for (let i = 0; i < elements.length; i++) {
      const element = elements[i];
      const timestamp = Number(element.getAttribute("timestamp"));
      element.textContent = TimeAgo.valueOf(timestamp);
      element.setAttribute("title", new Date(timestamp).toLocaleString());
    }
  }, 61 * 1000);
};

class SemVer {
  constructor(string) {
    const result = SemVer.REGEX.exec(string);
    if (!result) {
      throw new Error(`Invalid input: [${string}]`);
    }
    this.major = Number(result[1]) || 0;
    this.minor = Number(result[2]) || 0;
    this.patch = Number(result[3]) || 0;
    this.label = result[4] || "";
  }

  isGreaterThan(version) {
    return compare(this, version) > 0;
  }

  static compare(version, comparedTo) {
    if (!version || !comparedTo) {
      return 0;
    }
    for (let key of ["major", "minor", "patch", "label"]) {
      const left = version[key];
      const right = comparedTo[key];
      if (left != right)
        if (typeof left === "string" && typeof right === "string") {
          if (left === right) {
            return 0;
          }
          if (left.length == 0) {
            // 1.0.0 > 1.0.0-alpha
            return 1;
          }
          if (right.length == 0) {
            // 1.0.0-alpha < 1.0.0
            return -1;
          }
          return left.localeCompare(right);
        } else {
          return left > right ? 1 : -1;
        }
    }
    return 0;
  }
}

/* Named capture group is not supported everywhere... */
/* /^(?<major>\d+)(?:\.(?<minor>\d+)(?:\.(?<patch>\d+))?)?(?:-(?<label>.+))?$/ */
SemVer.REGEX = /^(\d+)(?:\.(\d+)(?:\.(\d+))?)?(?:-(.+))?$/;

function TimeAgo() {}

TimeAgo.valueOf = function(time) {
  const msPerMinute = 60 * 1000;
  const msPerHour = msPerMinute * 60;
  const msPerDay = msPerHour * 24;
  const msPerMonth = msPerDay * 30;
  const msPerYear = msPerDay * 365;
  const elapsed = Math.abs(Date.now() - time);
  const future = Date.now() - time < 0;
  if (elapsed < msPerMinute) {
    const count = Math.round(elapsed / 1000);
    if (count == 0) {
      return "now";
    }
    return `${TimeAgo.futurePrefix(future)} ${count} second${TimeAgo.pluralize(
      count
    )} ${TimeAgo.pastSuffix(future)}`;
  } else if (elapsed < msPerHour) {
    const count = Math.round(elapsed / msPerMinute);
    return `${TimeAgo.futurePrefix(future)} ${count} minute${TimeAgo.pluralize(
      count
    )} ${TimeAgo.pastSuffix(future)}`;
  } else if (elapsed < msPerDay) {
    const count = Math.round(elapsed / msPerHour);
    return `${TimeAgo.futurePrefix(future)} ${count} hour${TimeAgo.pluralize(
      count
    )} ${TimeAgo.pastSuffix(future)}`;
  } else if (elapsed < msPerMonth) {
    const count = Math.round(elapsed / msPerDay);
    return `${TimeAgo.futurePrefix(future)} ${count} day${TimeAgo.pluralize(
      count
    )} ${TimeAgo.pastSuffix(future)}`;
  } else if (elapsed < msPerYear) {
    const count = Math.round(elapsed / msPerMonth);
    return `${TimeAgo.futurePrefix(future)} ${count} month${TimeAgo.pluralize(
      count
    )} ${TimeAgo.pastSuffix(future)}`;
  } else {
    const count = Math.round(elapsed / msPerYear);
    return `${TimeAgo.futurePrefix(future)} ${count} year${TimeAgo.pluralize(
      count
    )} ${TimeAgo.pastSuffix(future)}`;
  }
};

TimeAgo.futurePrefix = function(future) {
  return future ? "in " : "";
};

TimeAgo.pastSuffix = function(future) {
  return future ? "" : " ago";
};

TimeAgo.pluralize = function(count) {
  return count > 1 ? "s" : "";
};

String.prototype.ellipsise = function(n, ellipsis = "&hellip;") {
  return this.substr(0, n - 1) + (this.length > n ? ellipsis : "");
};

Storage.prototype.setObject = function(key, value) {
  this.setItem(key, JSON.stringify(value));
};

Storage.prototype.getObject = function(key) {
  const value = this.getItem(key);
  try {
    return value && JSON.parse(value);
  } catch (e) {
    return undefined;
  }
};

/**
 * https://www.quaxio.com/html_white_listed_sanitizer/
 * Sanitizer which filters a set of whitelisted tags, attributes and css.
 * For now, the whitelist is small but can be easily extended.
 *
 * @param bool whether to escape or strip undesirable content.
 * @param map of allowed tag-attribute-attribute-parsers.
 * @param array of allowed css elements.
 * @param array of allowed url scheme
 */
function HtmlSanitizer(escape, tags, css, urls) {
  this.escape = escape;
  this.allowedTags = tags;
  this.allowedCss = css || ["border", "margin", "padding"];

  // Use the browser to parse the input but create a new HTMLDocument.
  // This won't evaluate any potentially dangerous scripts since the element
  // isn't attached to the window's document. It also won't cause img.src to
  // preload images.
  //
  // To be extra cautious, you can dynamically create an iframe, pass the
  // input to the iframe and get back the sanitized string.
  this.doc = document.implementation.createHTMLDocument();

  if (urls == null) {
    urls = ["http://", "https://"];
  }

  if (this.allowedTags == null) {
    // Configure small set of default tags
    var unconstrainted = function(x) {
      return x;
    };
    var globalAttributes = {
      title: unconstrainted,
      class: unconstrainted
    };
    var url_sanitizer = HtmlSanitizer.makeUrlSanitizer(urls);
    this.allowedTags = {
      a: HtmlSanitizer.mergeMap(globalAttributes, {
        href: url_sanitizer
      }),
      b: globalAttributes,
      big: globalAttributes,
      blockquote: globalAttributes,
      br: globalAttributes,
      cite: globalAttributes,
      div: globalAttributes,
      em: globalAttributes,
      h1: globalAttributes,
      h2: globalAttributes,
      h3: globalAttributes,
      h4: globalAttributes,
      h5: globalAttributes,
      h6: globalAttributes,
      i: globalAttributes,
      img: HtmlSanitizer.mergeMap(globalAttributes, {
        alt: unconstrainted,
        height: unconstrainted,
        src: url_sanitizer,
        width: unconstrainted
      }),
      li: globalAttributes,
      p: globalAttributes,
      u: globalAttributes,
      ul: globalAttributes,
      small: globalAttributes,
      span: globalAttributes,
      strike: globalAttributes,
      strong: globalAttributes,
      sub: globalAttributes,
      sup: globalAttributes
    };
  }
}

HtmlSanitizer.makeUrlSanitizer = function(allowed_urls) {
  return function(str) {
    if (!str) {
      return "";
    }
    for (var i in allowed_urls) {
      if (str.startsWith(allowed_urls[i])) {
        return str;
      }
    }
    return "";
  };
};

HtmlSanitizer.mergeMap = function(/*...*/) {
  var r = {};
  for (var arg in arguments) {
    for (var i in arguments[arg]) {
      r[i] = arguments[arg][i];
    }
  }
  return r;
};

HtmlSanitizer.prototype.sanitizeString = function(input) {
  var div = this.doc.createElement("div");
  div.innerHTML = input;
  // Return the sanitized version of the node.
  return this.sanitizeNode(div).innerHTML;
};

HtmlSanitizer.prototype.sanitizeNode = function(node) {
  var node_name = node.nodeName.toLowerCase();
  if (node_name == "#text") {
    return node;
  }
  if (node_name == "#comment") {
    return this.doc.createTextNode("");
  }
  if (!this.allowedTags.hasOwnProperty(node_name)) {
    console.log("forbidden node: " + node_name);
    if (this.escape) {
      return this.doc.createTextNode(node.outerHTML);
    }
    return this.doc.createTextNode("");
  }
  var copy = this.doc.createElement(node_name);
  for (var n_attr = 0; n_attr < node.attributes.length; n_attr++) {
    var attr = node.attributes.item(n_attr).name;
    if (this.allowedTags[node_name].hasOwnProperty(attr)) {
      var sanitizer = this.allowedTags[node_name][attr];
      copy.setAttribute(attr, sanitizer(node.getAttribute(attr)));
    }
  }
  for (var css in this.allowedCss) {
    copy.style[this.allowedCss[css]] = node.style[this.allowedCss[css]];
  }
  while (node.childNodes.length > 0) {
    var child = node.removeChild(node.childNodes[0]);
    copy.appendChild(this.sanitizeNode(child));
  }
  return copy;
};

HtmlSanitizer.sanitize = function(value) {
  return new HtmlSanitizer(true).sanitizeString(value);
};
