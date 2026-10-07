package com.datasync.server.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 生产环境启动前置校验：必需配置缺失就拒绝启动，并把缺的变量名逐条列出来。
 *
 * <h2>为什么需要这个类（而不是靠 yml 里的 {@code ${VAR:?提示}}）</h2>
 * {@code application-prod.yml} 里确实写了 {@code ${VAR:?MISSING_ENV_XXX}}，但**实测在本项目环境下
 * 它不会抛异常也不会让启动失败**：环境变量缺失时占位符被替换成那段字面量提示文本，
 * 于是应用带着一个假 JDBC URL 继续启动，最终炸在 MySQL 驱动的
 * {@code Malformed database URL, failed to parse the connection string near ':3306/?MISSING_ENV_...'}。
 * 运维看到的是驱动解析错误，完全不知道缺的是哪个环境变量。所以必须显式校验。
 *
 * <h2>为什么必须是 BeanFactoryPostProcessor（这一点是实测踩出来的，别改回去）</h2>
 * 先后试过两种更"自然"的写法，都被证明太晚：
 * <ol>
 *   <li>{@link org.springframework.boot.ApplicationRunner}：要等整个上下文就绪，Flyway 早就连库炸了。</li>
 *   <li>{@link org.springframework.beans.factory.InitializingBean}：虽然在本 Bean 注入完成后即执行，
 *       但 Spring 创建 Bean 的顺序由依赖图决定；本 Bean 不依赖 DataSource，于是容器**先**创建了
 *       {@code dataSource} / {@code flywayInitializer}，异常在 Flyway 那里先抛了出来，
 *       我们精心准备的清单根本没机会打印（实测日志里看到的就是 Malformed database URL）。</li>
 * </ol>
 * {@link BeanFactoryPostProcessor} 在**所有普通 Bean 实例化之前**执行，因此这里的失败信息
 * 一定是用户看到的第一条、也是唯一一条——这正是我们要的效果。
 *
 * <h2>为什么用 EnvironmentAware 而不是构造器注入（也是实测踩出来的）</h2>
 * {@code BeanFactoryPostProcessor} 由 {@code PostProcessorRegistrationDelegate} 在
 * {@code AutowiredAnnotationBeanPostProcessor} 注册**之前**就实例化，此时构造器注入尚未生效，
 * 带参构造器会直接抛 {@code No default constructor found} 让应用起不来。
 * 因此这里保留无参构造器，改用 {@link EnvironmentAware} 回调拿到 {@link Environment}。
 *
 * <h2>为什么只在 prod 生效</h2>
 * dev profile 刻意免密免登录（方便本地调接口），不能要求它填生产密钥。
 * 用 {@code @Profile("prod")} 判断，而不是猜"是否看起来像生产"，避免误伤。
 */
@Component
@Profile("prod")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ProductionConfigValidator implements BeanFactoryPostProcessor, EnvironmentAware {

    private static final Logger log = LoggerFactory.getLogger(ProductionConfigValidator.class);

    /** AES-GCM 密钥的最小长度（字符）。256 位密钥用 32 个字符即可。 */
    private static final int MIN_SECRET_KEY_LENGTH = 32;

    /** 由 {@link EnvironmentAware} 回调注入；不使用构造器注入（见类注释）。 */
    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        if (environment == null) {
            // 理论上不会发生；真发生了就大声失败，绝不静默跳过校验
            throw new IllegalStateException(
                    "生产环境配置校验器未能获取 Environment，拒绝启动以保证不会带着未校验的配置运行");
        }

        List<String> problems = new ArrayList<>();

        checkMissing(problems, "DATASYNC_DB_HOST", "元数据库主机");
        checkMissing(problems, "DATASYNC_DB_NAME", "元数据库名");
        checkMissing(problems, "DATASYNC_DB_USER", "元数据库账号");
        checkMissing(problems, "DATASYNC_DB_PASSWORD", "元数据库口令");
        checkMissing(problems, "DATASYNC_ADMIN_PASSWORD", "管理界面登录口令");

        if (!environment.getProperty("app.security.enabled", Boolean.class, true)) {
            problems.add("app.security.enabled 为 false —— 生产环境不允许关闭认证"
                    + "（请把 DATASYNC_AUTH_ENABLED 设为 true，或删除该环境变量）");
        }

        String secretKey = value("DATASYNC_SECRET_KEY");
        if (secretKey.isBlank()) {
            problems.add("DATASYNC_SECRET_KEY 未配置 —— 它是数据源口令 AES-GCM 加密的密钥，必须显式提供");
        } else if (secretKey.length() < MIN_SECRET_KEY_LENGTH) {
            problems.add("DATASYNC_SECRET_KEY 长度为 " + secretKey.length()
                    + "，少于要求的 " + MIN_SECRET_KEY_LENGTH + " 个字符");
        }

        if (problems.isEmpty()) {
            log.info("生产环境配置校验通过：认证已开启，元数据库连接信息与加密密钥均已显式配置");
            return;
        }

        StringBuilder message = new StringBuilder();
        message.append("生产环境配置校验未通过，拒绝启动。共 ").append(problems.size()).append(" 项问题：\n");
        for (int i = 0; i < problems.size(); i++) {
            message.append("  ").append(i + 1).append(") ").append(problems.get(i)).append('\n');
        }
        message.append("请参考项目根目录的 .env.example 补齐这些环境变量后重新启动。");
        throw new IllegalStateException(message.toString());
    }

    /**
     * 判断某个环境变量是否"没配"。除了空白，还要拦住"被 {@code ${VAR:?...}} 替换成提示文本"的情况，
     * 因此一并检查值里是否含 MISSING_ENV 标记。
     */
    private void checkMissing(List<String> problems, String envName, String description) {
        String raw = value(envName);
        if (raw.isBlank() || raw.contains("MISSING_ENV_")) {
            problems.add(envName + " 未配置（" + description + "）");
        }
    }

    private String value(String name) {
        String v = environment.getProperty(name);
        return v == null ? "" : v.trim();
    }
}
