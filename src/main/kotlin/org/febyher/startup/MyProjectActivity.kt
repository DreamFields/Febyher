package org.febyher.startup

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * 项目打开后执行（可选：审计/权限预热等）
 */
class MyProjectActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        // 预留：如需在项目加载时做初始化可在此执行
    }
}
