# CampusMarket 第一轮 UI 改造验收记录

日期：2026-10-26

## 实施结果

- 视觉基线改为黑、白、灰，`#A0BA00` 只用于关键按钮、选中边框和强调标记。
- 强调色背景统一使用深色文字，未使用低对比度的白字组合。
- 商品列表改为 4:3 纵向大图卡片；竖屏单列，横屏两列。
- 商品详情采用 1:1 大图，标题、价格、属性、操作、描述和卖家信息依次排列；操作区随内容滚动。
- 发布/编辑表单按基本信息、交易信息、描述和图片分区，保留原校验规则并增加字段级错误位置。
- 收藏继续复用商品卡片；订单改用独立交易卡片和结构化详情；消息改为分层会话卡片和左右消息气泡。
- 未修改 Servlet、API、数据库结构、业务状态机或数据库记录。

## 构建与测试

| 验证 | 结果 |
|---|---|
| 公共资源 + 浏览组：`testDebugUnitTest`、`assembleDebug` | PASS |
| 发布/收藏/订单：Catalog、Publishing、Order 接口回归 + APK | 9 tests PASS |
| 最终 JVM 回归 | 10 tests PASS，0 failures |
| Debug APK | PASS，3,015,013 bytes |
| Android Lint | PASS，0 errors，3 warnings |
| Medium Phone API 36.1 搜索 UI 流程 | 1 test PASS，两次执行均通过 |

最终 APK SHA-256：`3D480496BC203B722D483F46E1AD047AFC26E7BB7815651F933AC98CBCD725AA`

Lint 保留的三项原有警告：SharedPreferences 同步提交建议、Gradle 版本提示、Gson 新版本提示。本轮新增的 Overdraw、LabelFor、UnusedResources 等警告已全部清理。

## 中文搜索专项结果

- 输入控件：通过 `InputConnection.setComposingText/finishComposingText` 验证中文组合输入，未强制 ASCII，Hint Locale 为 `zh-CN`。
- 粘贴：将“无线鼠标”写入剪贴板，经 App 粘贴入口搜索，正确返回一条匹配商品。
- 编码：`CatalogEncodingTest` 验证中英文和保留字符按 UTF-8 URL 编码，1 test PASS。
- 后端匹配：设备端搜索“高等数学”正确返回对应 MySQL 商品；不存在的英文关键词显示空状态。
- 组合回归：分页、分类、价格排序、清空关键词、商品详情、数据库连接、横竖屏均通过。
- 模拟器输入法语言包状态不属于搜索接口验证范围；本次通过 Android 组合输入 API 和粘贴分别验证 App 输入链路。

## 正式截图

- `home-verified.png`：竖屏首页和纵向大图卡片。
- `product-detail.png`：商品详情和随内容滚动的操作区。
- `chinese-search.png`：中文组合输入及后端匹配结果。
- `landscape.png`：横屏筛选区和两列纵向卡片。

`auth.png` 与 `publish-or-auth.png` 是登录页启用 `FLAG_SECURE` 后由系统生成的黑色安全截图，不属于 UI 验收证据。为避免创建测试账号或写入数据库，本轮没有绕过安全标志获取登录后页面截图。

## 修改文件

### Java UI 绑定

- `android-client/app/src/main/java/com/campusmarket/AspectRatioFrameLayout.java`
- `android-client/app/src/main/java/com/campusmarket/CatalogController.java`
- `android-client/app/src/main/java/com/campusmarket/ProductDetailActivity.java`
- `android-client/app/src/main/java/com/campusmarket/ProductEditActivity.java`
- `android-client/app/src/main/java/com/campusmarket/OrdersActivity.java`
- `android-client/app/src/main/java/com/campusmarket/OrderDetailActivity.java`
- `android-client/app/src/main/java/com/campusmarket/ConversationsActivity.java`
- `android-client/app/src/main/java/com/campusmarket/ChatActivity.java`

### 公共资源

- `values/market_colors.xml`、`values/dimens.xml`、`values/styles.xml`
- `values/publishing_strings.xml`、`values/order_strings.xml`、`values/messaging_strings.xml`
- `color/navigation_text.xml`、`color/button_primary_text.xml`、`color/button_secondary_text.xml`
- `drawable/button_primary_background.xml`、`button_secondary_background.xml`、`button_danger_background.xml`
- `drawable/input_background.xml`、`status_accent_background.xml`
- `drawable/card_background.xml`、`search_background.xml`、`navigation_background.xml`、`thumbnail_background.xml`
- `drawable/ic_launcher.xml`

### 布局

- `layout/catalog_panel.xml`、`layout-land/catalog_panel.xml`
- `layout/item_product.xml`、`layout/activity_product_detail.xml`
- `layout/activity_product_edit.xml`、`layout/activity_my_products.xml`
- `layout/activity_orders.xml`、`layout/item_order.xml`、`layout/activity_order_detail.xml`
- `layout/activity_conversations.xml`、`layout/item_conversation.xml`
- `layout/activity_chat.xml`、`layout/item_message.xml`
- `layout/activity_main.xml`、`layout/profile_panel.xml`
- `layout/activity_auth.xml`、`layout/activity_edit_profile.xml`

## 限制

- 发布、收藏、订单和消息页面已完成资源编译、JVM 接口回归和 Lint；本轮未创建账号或业务数据，因此没有登录后页面截图。
- 登录页使用现有 `FLAG_SECURE`，ADB 截屏会得到黑色画面，这是既有安全行为，不是渲染失败。
- 消息模块当前没有独立 JVM 集成测试类；本轮验证范围为编译、资源绑定、Lint 和既有轮询代码保持不变。
