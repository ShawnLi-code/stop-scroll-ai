# -*- coding: utf-8 -*-
"""
AI 屏幕内容监督判定模拟测试脚本 (电脑端直接运行)
用于验证：当用户设定不同目标时，AI 能否准确从屏幕抓取的文字中识别出“正常查找”与“偏离摸鱼”。
"""

import json
import urllib.request
import urllib.error

# 你的 API Key 配置 (如 DeepSeek)
API_KEY = "sk-xxxxxx"  # 替换成你的真实 API Key 测试
API_URL = "https://api.deepseek.com/chat/completions"
MODEL = "deepseek-chat"

def check_relevance(user_goal: str, screen_text: str):
    system_prompt = (
        "你是一个严格的手机自律与防沉迷监督AI。用户在使用应用前明确声明了自己的目标。\n"
        "现在给你当前手机屏幕上提取的文本内容。\n"
        "请判断用户当前浏览或操作的内容是否严重偏离了其预定目标。\n"
        "规则：\n"
        "1. 搜索框操作、寻找相关教程、评论区讨论教程属于正常行为，回答 NO。\n"
        "2. 只有当用户正在浏览明显无关的纯娱乐、八卦、搞笑、无意义短视频、美女帅哥热舞等，才算严重偏离。\n"
        "3. 输出格式必须严格为：YES|偏离原因 或者是 NO。例如：YES|当前正在刷明星八卦视频。"
    )
    user_message = f"用户声明的目标: {user_goal}\n当前屏幕文本提取采样:\n{screen_text[:500]}"
    
    payload = {
        "model": MODEL,
        "messages": [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_message}
        ],
        "temperature": 0.1,
        "max_tokens": 50
    }
    
    headers = {
        "Authorization": f"Bearer {API_KEY}",
        "Content-Type": "application/json"
    }
    
    req = urllib.request.Request(API_URL, data=json.dumps(payload).encode("utf-8"), headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            return data["choices"][0]["message"]["content"].strip()
    except Exception as e:
        return f"请求失败: {e}"

if __name__ == "__main__":
    print("=" * 60)
    print("🤖 别刷了 AI - 屏幕文本理解决策测试")
    print("=" * 60)
    
    test_goal = "搜索番茄炒蛋做饭教程"
    print(f"📌 设定用户目标: 【{test_goal}】\n")
    
    test_cases = [
        {
            "name": "场景 1：小红书搜索做饭笔记（正轨）",
            "screen": "小红书 | 搜索：番茄炒蛋 | 秘制番茄炒蛋，汤汁浓郁超下饭！鸡蛋打散加少许盐，热锅下油翻炒至凝固盛出。西红柿切块下锅翻炒出汁..."
        },
        {
            "name": "场景 2：小红书首页乱刷搞笑段子（偏离摸鱼）",
            "screen": "小红书 | 爆笑生活 | 当你第一天去上班遇到的奇葩领导，哈哈哈哈笑死我了，连看3遍停不下来！网友热评：太真实了！"
        },
        {
            "name": "场景 3：小红书推荐流刷到热舞短视频（偏离摸鱼）",
            "screen": "小红书 | 颜值穿搭 | 今日ootd挑战，纯欲风穿搭，甜妹热舞教学，这个BGM太魔性了！音乐：蹦迪金曲"
        }
    ]
    
    for case in test_cases:
        print(f"👉 模拟测试：{case['name']}")
        print(f"   屏幕抓取文本：{case['screen'][:60]}...")
        if API_KEY == "sk-xxxxxx":
            print("   ⚠️ [提示] 尚未填入 API Key，若需在电脑测试请将脚本中的 API_KEY 替换成你的真实 Key。\n")
        else:
            result = check_relevance(test_goal, case['screen'])
            print(f"   🤖 AI 判定结果: {result}\n")
