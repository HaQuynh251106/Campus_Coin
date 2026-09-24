/** @type {import('tailwindcss').Config} */
module.exports = {
  darkMode: 'class',
  content: [
    './src/**/*.{html,ts,scss}'
  ],
  theme: {
    extend: {
      colors: {
        brand: {
          gold: '#EAB308',
          'gold-hover': '#CA8A04',
          'gold-tint': 'rgba(234, 179, 8, 0.1)',
          'gold-dark': '#A16207',
          // backward-compat aliases pointing to refined gold
          yellow: '#EAB308',
          mint: '#10B981',
          coral: '#EF4444',
          pink: '#EAB308',
          cyan: '#0284C7',
          purple: '#8B5CF6',
          bg: '#F9FAFB',
          dark: '#09090B',
          'dark-surface': '#18181B',
          'dark-card': '#18181B',
          'dark-border': '#27272A'
        }
      },
      boxShadow: {
        'subtle': '0 1px 3px 0 rgba(0, 0, 0, 0.05), 0 1px 2px -1px rgba(0, 0, 0, 0.05)',
        'subtle-lg': '0 4px 6px -1px rgba(0, 0, 0, 0.07), 0 2px 4px -2px rgba(0, 0, 0, 0.05)',
        'brutal-sm': '0 1px 2px 0 rgba(0, 0, 0, 0.05)',
        'brutal': '0 1px 3px 0 rgba(0, 0, 0, 0.05), 0 1px 2px -1px rgba(0, 0, 0, 0.05)',
        'brutal-lg': '0 4px 6px -1px rgba(0, 0, 0, 0.07), 0 2px 4px -2px rgba(0, 0, 0, 0.05)',
        'brutal-xl': '0 10px 15px -3px rgba(0, 0, 0, 0.07), 0 4px 6px -4px rgba(0, 0, 0, 0.05)',
        'brutal-white-sm': '0 1px 2px 0 rgba(0, 0, 0, 0.3)',
        'brutal-white': '0 1px 3px 0 rgba(0, 0, 0, 0.4)',
        'brutal-white-lg': '0 4px 6px -1px rgba(0, 0, 0, 0.4)'
      },
      fontFamily: {
        heading: ['Inter', 'system-ui', 'sans-serif'],
        sans: ['Inter', 'system-ui', 'sans-serif']
      },
      borderRadius: {
        'card': '12px'
      }
    }
  },
  plugins: []
};
