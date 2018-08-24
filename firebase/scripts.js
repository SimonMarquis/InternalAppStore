/* List of useful scripts */

function refresh_apk_properties() {
    window.store.databaseRefs.store.once("value").then(snapshot => {
        const store = snapshot.val();
        if (!store || !store.applications || !store.versions) {
            return;
        }
        for (const [applicationKey, application] of Object.entries(store.applications)) {
            for (const [versionKey, version] of Object.entries(store.versions[applicationKey] || {})) {
                const versionRef = window.store.databaseRefs.version(applicationKey, versionKey);
                if (version.apkRef) {
                    window.store.storageRefs.of(version.apkRef).getMetadata().then(function(metadata) {
                        versionRef.update({
                            apkUrl: null,
                            apkSize: metadata.size
                        }).then(function(success) {
                            console.log(application.name, version.name, "Updated to", metadata.size);
                        }).catch(function(error) {
                            console.error(error);
                        });
                    }).catch(function(error) {
                        console.error(error);
                    });
                } else {
                    versionRef.update({
                        apkRef: null,
                        apkSize: null,
                        apkGeneration: null
                    }).catch(function(error) {
                        console.error(error);
                    });
                }
            }
        }
    });
}
