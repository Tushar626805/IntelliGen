
/*
 * IntelliGen shared API layer.
 * Load before other page scripts.
 */
(function () {
    'use strict';

    var nativeFetch = window.fetch.bind(window);

    var AUTH_PATHS =
        /\/api\/auth\/(login|logout|register|forgot-password|reset-password)(\?|$)/;

    function readCookie(name) {
        var parts = document.cookie ? document.cookie.split('; ') : [];

        for (var i = 0; i < parts.length; i++) {
            var idx = parts[i].indexOf('=');

            if (idx > -1 && parts[i].substring(0, idx) === name) {
                try {
                    return decodeURIComponent(parts[i].substring(idx + 1));
                } catch (e) {
                    return null;
                }
            }
        }

        return null;
    }

    function parse(url) {
        try {
            return new URL(url, window.location.href);
        } catch (e) {
            return null;
        }
    }

    function clearLocalUser() {
        try {
            localStorage.removeItem('currentUser');
        } catch (e) {}
    }

    function sessionExpired() {
        clearLocalUser();

        if (!/login\.html$/.test(window.location.pathname)) {
            window.location.replace('login.html?expired=1');
        }
    }

    window.fetch = function (input, init) {
        var url = typeof input === 'string'
            ? input
            : (input && input.url) || '';

        var parsed = parse(url);

        if (!parsed || parsed.origin !== window.location.origin) {
            return nativeFetch(input, init);
        }

        init = init ? Object.assign({}, init) : {};

        var method = String(
            init.method || (input && input.method) || 'GET'
        ).toUpperCase();

        if (!init.credentials) {
            init.credentials = 'same-origin';
        }

        if (method !== 'GET' &&
            method !== 'HEAD' &&
            method !== 'OPTIONS') {

            var token = readCookie('XSRF-TOKEN');

            if (token) {
                var headers = new Headers(
                    init.headers || (input && input.headers) || {}
                );

                if (!headers.has('X-XSRF-TOKEN')) {
                    headers.set('X-XSRF-TOKEN', token);
                }

                init.headers = headers;
            }
        }

        return nativeFetch(input, init).then(function (res) {
            if (
                res.status === 401 &&
                parsed.pathname.indexOf('/api/') === 0 &&
                !AUTH_PATHS.test(parsed.pathname + parsed.search)
            ) {
                sessionExpired();
            }

            return res;
        });
    };

    function endServerSession() {
        var headers = {
            'Content-Type': 'application/json'
        };

        var token = readCookie('XSRF-TOKEN');

        if (token) {
            headers['X-XSRF-TOKEN'] = token;
        }

        return nativeFetch('/api/auth/logout', {
            method: 'POST',
            credentials: 'same-origin',
            headers: headers
        }).catch(function () {});
    }

    function upload(url, formData, options) {
        options = options || {};

        return new Promise(function (resolve, reject) {
            var xhr = new XMLHttpRequest();

            xhr.open('POST', url);
            xhr.withCredentials = true;
            xhr.setRequestHeader('Accept', 'application/json');

            var token = readCookie('XSRF-TOKEN');

            if (token) {
                xhr.setRequestHeader('X-XSRF-TOKEN', token);
            }

            if (xhr.upload && options.onProgress) {
                xhr.upload.onprogress = function (e) {
                    if (e.lengthComputable && e.total > 0) {
                        options.onProgress(e.loaded / e.total);
                    }
                };
            }

            xhr.onload = function () {
                if (xhr.status === 401) {
                    sessionExpired();
                }

                var body = null;

                try {
                    body = JSON.parse(xhr.responseText);
                } catch (e) {}

                resolve({
                    ok: xhr.status >= 200 && xhr.status < 300,
                    status: xhr.status,
                    json: body,
                    text: xhr.responseText
                });
            };

            xhr.onerror = function () {
                reject(new Error('network'));
            };

            xhr.onabort = function () {
                reject(new Error('aborted'));
            };

            if (options.signal) {
                if (options.signal.aborted) {
                    reject(new Error('aborted'));
                    return;
                }

                options.signal.addEventListener('abort', function () {
                    xhr.abort();
                });
            }

            xhr.send(formData);
        });
    }

    window.IGApi = {
        upload: upload,

        logout: function () {
            return endServerSession().then(function () {
                clearLocalUser();
                window.location.replace('login.html');
            });
        },

        endSessionQuietly: function () {
            var had = false;

            try {
                had = !!localStorage.getItem('currentUser');
            } catch (e) {}

            clearLocalUser();

            return had
                ? endServerSession()
                : Promise.resolve();
        }
    };
})();
