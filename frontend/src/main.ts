import { createApp } from 'vue'
import './style.css'
import App from './App.vue'
import router from './router'

// Element Plus 按需自动导入（unplugin），无需全量注册与全量样式
const app = createApp(App)
app.use(router)
app.mount('#app')
