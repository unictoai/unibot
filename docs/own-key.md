# Bring your own key · 换成自己的 key

[中文](#中文) · [English](#english)

unibot has no account and no sign-up: the app is free and open source,
and the model behind it is whatever key you paste in. This page walks through
one provider, **Alibaba Cloud Bailian (阿里云百炼)**, in five steps — about two
minutes — and then shows how to fill in any other OpenAI-compatible provider.

There is no account to affect: only the model provider changes. With your own
key, your words go straight to the provider you chose — nothing passes through
unibot servers, because there are none.

---

## 中文

### 为什么推荐阿里云百炼

- **新用户有免费额度**：开通后主流模型都送一段时间的免费 token，先用起来不花钱。
- **一个 key 就够**：对话（Qwen）、画图（qwen-image）、视频（Wan）都在同一个 key 下，unibot 的形象生成也能直接用。
- **国内直连**，速度稳定；接口是 OpenAI 兼容的，unibot 里已经预置好了地址，只需要贴 key。

### 五步开通（约 2 分钟）

1. **开通百炼。** 打开 [bailian.console.aliyun.com](https://bailian.console.aliyun.com/)，用阿里云账号登录（没有的话注册一个，需要实名认证）。第一次进入会提示「开通百炼服务」，点开通即可，不收费。
2. **创建 API key。** 进入 [API-KEY 管理页](https://bailian.console.aliyun.com/?apiKey=1)（右上角头像 → API-KEY），点「创建我的 API-KEY」，归属选默认业务空间，确定。
3. **复制 key。** 新建的 key 以 `sk-` 开头。点「查看」再「复制」。**这串字符只给 unibot 用，不要发给任何人、不要贴到聊天里**。
4. **贴到 unibot 里。**
   - 手机：额度用完时卡片上的「去设置」会直接打开预填好的「阿里云百炼」表单；或者 设置 → 服务商 → 添加 → OpenAI 类型，地址填 `https://dashscope.aliyuncs.com/compatible-mode`（勾上 `/v1`）。把 key 贴进去，保存。
   - 网页版 / 桌面版：额度卡片上的「去设置」会打开「连接」页并选好「阿里云百炼」；或者 连接 → 模型 → 选「阿里云百炼」。贴 key，保存。
5. **选一个模型，试一句。** 保存后 unibot 会自动列出这个 key 能用的模型；对话选 `qwen3.8-flash`（便宜快）或 `qwen3.8-27b`（更强）。回到聊天说一句话，能回答就成了。想让它换形象，再到 连接 → 图像与视频模型 里选 `qwen-image-3.0` 和 `wan2.2-i2v-flash`。

### 之后怎么算钱

免费额度用完后按百炼的标价计费（2026 年 9 月，北京地域）：`qwen3.8-27b` 每百万 token 输入 ¥3 / 输出 ¥12，`qwen3.8-flash` ¥0.8 / ¥2.7，`qwen-image-3.0` 每张 ¥0.18，`wan2.2-i2v-flash` 每秒 ¥0.10。正常聊一天几分钱；建议在百炼控制台设一个**用量告警**。unibot 自己不收任何费用。

### 其他 OpenAI 兼容服务商

任何 OpenAI 兼容接口都能填：DeepSeek、智谱、月之暗面、OpenRouter、OpenAI 本身，或你自己跑的 vLLM / Ollama。通用填法：

| 字段 | 填什么 |
|---|---|
| 类型 | OpenAI（兼容） |
| 地址（Base URL） | 服务商给的地址，通常以 `/v1` 结尾，例如 `https://api.deepseek.com/v1` |
| API key | 服务商控制台里创建的 key |
| 模型 | 保存后从列表里选；列不出来就手填服务商文档里的模型 id |

网页版和桌面版的「连接」页里有常见服务商的预设，选中即填好地址，只差 key。

---

## English

### Why Alibaba Cloud Bailian first

- **A free quota for new accounts**: the main models come with free tokens for a while after sign-up.
- **One key covers everything**: chat (Qwen), pictures (qwen-image) and video (Wan) all sit under the same key, so unibot's avatar studio works too.
- **Direct from China, OpenAI-compatible**: unibot already knows the endpoint; you only paste the key.

### Five steps (about two minutes)

1. **Open Bailian.** Go to [bailian.console.aliyun.com](https://bailian.console.aliyun.com/) and sign in with an Alibaba Cloud account (create one if needed; identity verification is required). On first entry, accept *Enable Model Studio* — it is free.
2. **Create an API key.** Open the [API-KEY page](https://bailian.console.aliyun.com/?apiKey=1) (avatar, top right → API-KEY), click *Create my API-KEY*, keep the default workspace, confirm.
3. **Copy it.** The key starts with `sk-`. Click *View*, then *Copy*. **It is for unibot only — never send it to anyone or paste it into a chat.**
4. **Paste it into unibot.**
   - Phone: *Set it up* on the allowance card opens the provider form pre-filled for Bailian; or *Settings → Providers → Add → OpenAI*, base URL `https://dashscope.aliyuncs.com/compatible-mode` with `/v1` on. Paste the key, save.
   - Web / desktop: *Set it up* on the allowance card opens *Connections* with *Alibaba Cloud Bailian* chosen; or *Connections → Model → Alibaba Cloud Bailian*. Paste the key, save.
5. **Pick a model, say something.** After saving, unibot lists the models the key can use; take `qwen3.8-flash` (cheap and quick) or `qwen3.8-27b` (stronger) for chat. Back in the chat, one sentence answered means it works. For the avatar, choose `qwen-image-3.0` and `wan2.2-i2v-flash` under *Connections → Image & video models*.

### What it costs afterwards

Past the free quota, Bailian bills at its list prices (September 2026, Beijing region): `qwen3.8-27b` ¥3 in / ¥12 out per million tokens, `qwen3.8-flash` ¥0.8 / ¥2.7, `qwen-image-3.0` ¥0.18 a picture, `wan2.2-i2v-flash` ¥0.10 a second. An ordinary day of chatting is a few fen; set a **usage alert** in the Bailian console. unibot itself charges nothing.

### Any other OpenAI-compatible provider

Anything that speaks the OpenAI API works: DeepSeek, Zhipu, Moonshot, OpenRouter, OpenAI itself, or your own vLLM / Ollama. The generic fill-in:

| Field | Value |
|---|---|
| Type | OpenAI (compatible) |
| Base URL | the provider's address, usually ending in `/v1`, e.g. `https://api.deepseek.com/v1` |
| API key | the key made in the provider's console |
| Model | pick from the list after saving; if none appears, type the model id from the provider's docs |

The web and desktop *Connections* page has presets for the common providers: choose one and the address is filled in — only the key is missing.
