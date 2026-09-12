import pluginVue from 'eslint-plugin-vue'
import globals from 'globals'
import { withVueTs, vueTsConfigs } from '@vue/eslint-config-typescript'
import eslintConfigPrettier from 'eslint-config-prettier'

export default withVueTs(
  {
    rootDir: import.meta.dirname,
  },
  {
    ignores: [
      'dist/**',
      'node_modules/**',
      'playwright-report/**',
      'test-results/**',
      'src/auto-imports.d.ts',
      'src/components.d.ts',
    ],
  },
  pluginVue.configs['flat/recommended'],
  vueTsConfigs.recommendedTypeChecked,
  vueTsConfigs.stylisticTypeChecked,
  {
    files: ['src/**/*.{ts,tsx,vue}'],
    languageOptions: {
      globals: globals.browser,
    },
    rules: {
      '@typescript-eslint/consistent-type-imports': [
        'error',
        {
          fixStyle: 'inline-type-imports',
          prefer: 'type-imports',
        },
      ],
      '@typescript-eslint/no-explicit-any': 'error',
      '@typescript-eslint/no-floating-promises': 'error',
      '@typescript-eslint/no-misused-promises': 'error',
      'no-console': ['error', { allow: ['warn', 'error'] }],
      'vue/component-name-in-template-casing': ['error', 'PascalCase'],
      'vue/multi-word-component-names': 'error',
      'vue/no-v-html': 'error',
    },
  },
  {
    files: ['e2e/**/*.ts'],
    languageOptions: {
      globals: globals.node,
    },
  },
  {
    files: ['**/*.spec.ts'],
    rules: {
      'vue/one-component-per-file': 'off',
    },
  },
  {
    // eslint-plugin-vue 无法追踪 template src 中消费的 script-setup 绑定；仅对三个外置模板页面关闭误报。
    files: ['src/views/CaseDashboard.vue', 'src/views/CaseDetailView.vue', 'src/views/ReviewView.vue'],
    rules: {
      '@typescript-eslint/no-unused-vars': 'off',
    },
  },
  eslintConfigPrettier,
)
