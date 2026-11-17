/* IntelliGen shared Tailwind theme.
   Load directly AFTER the Tailwind CDN script. Remaps the blue/indigo/purple
   scales to ONE navy brand palette (primary #1F4E96) so every existing utility class
   (bg-blue-600, text-purple-600, ...) follows the same theme. */
(function () {
    var brand = {
        50: '#eef3fb', 100: '#dbe6f6', 200: '#b7cdec', 300: '#8aaedd', 400: '#5b8bcb',
        500: '#2f66b3', 600: '#1f4e96', 700: '#183d78', 800: '#142f5c', 900: '#0f2347', 950: '#0f2347'
    };
    var navy = {
        50: '#f8fafc', 100: '#f1f5f9', 200: '#e2e8f0', 300: '#cbd5e1', 400: '#94a3b8',
        500: '#64748b', 600: '#475569', 700: '#334155', 800: '#14284f', 900: '#0f2347', 950: '#0a1730'
    };
    tailwind.config = {
        theme: {
            extend: {
                fontFamily: { sans: ['Inter', 'ui-sans-serif', 'system-ui', '-apple-system', 'Segoe UI', 'Roboto', 'sans-serif'] },
                colors: { brand: brand, blue: brand, indigo: brand, purple: brand, violet: brand, navy: navy,
                    /* slate-400 is used for secondary text; the Tailwind default (#94a3b8) is only 2.6:1 on white */
                    slate: { 400: '#5f6f86' } }
            }
        }
    };
})();
