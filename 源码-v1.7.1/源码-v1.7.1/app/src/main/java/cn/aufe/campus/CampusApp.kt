package cn.aufe.campus

import android.app.Application
import cn.aufe.campus.auth.AuthManager
import cn.aufe.campus.util.WeekProvider

class CampusApp : Application() {

    lateinit var authManager: AuthManager
        private set

    override fun onCreate() {
        super.onCreate()
        // 周次状态需要在 Application 里初始化：课表页、桌面小组件、上课提醒都会读它
        WeekProvider.init(this)
        authManager = AuthManager(this)
    }
}
