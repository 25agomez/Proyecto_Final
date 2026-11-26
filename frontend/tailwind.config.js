/** Tailwind configuration - sober industrial palette (P7). */
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  darkMode: 'class',
  theme: {
    extend: {
      colors: {
        surface: {
          DEFAULT: '#0f172a',
          muted: '#1e293b',
          border: '#334155',
        },
        status: {
          ok: '#16a34a',
          warn: '#d97706',
          danger: '#dc2626',
          info: '#2563eb',
        },
      },
      fontFamily: {
        sans: ['Inter', 'system-ui', 'sans-serif'],
      },
    },
  },
  plugins: [],
};
