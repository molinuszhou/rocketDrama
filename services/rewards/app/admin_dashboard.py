from __future__ import annotations


DASHBOARD_HTML = """<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width,initial-scale=1">
  <title>JoyeDrama 奖励运营后台</title>
  <style>
    :root { color-scheme:dark; --bg:#121018; --panel:#201a2d; --accent:#ff5d69; --gold:#ffdb6a; --muted:#bfb5cd; --line:#403457; }
    * { box-sizing:border-box; } body { margin:0; min-height:100vh; background:linear-gradient(135deg,#15101f,#101a28); color:#fff; font:15px system-ui,-apple-system,"Microsoft YaHei",sans-serif; }
    main { width:min(800px,calc(100% - 32px)); margin:32px auto; } h1 { margin:0 0 8px; font-size:27px; } h2 { margin:0; font-size:20px; } .intro { color:var(--muted); line-height:1.65; }
    .card { background:rgba(32,26,45,.94); border:1px solid var(--line); border-radius:18px; padding:20px; margin-top:18px; box-shadow:0 16px 40px rgba(0,0,0,.18); }
    label { display:block; color:var(--muted); margin:14px 0 7px; font-size:13px; } input { width:100%; border:1px solid #514466; border-radius:10px; background:#15111f; color:#fff; padding:12px; font-size:15px; }
    button { border:0; border-radius:10px; padding:12px 15px; margin:14px 8px 0 0; background:var(--accent); color:#fff; font-weight:700; cursor:pointer; } button.secondary { background:#332b43; color:var(--gold); } button:disabled { cursor:not-allowed; opacity:.55; }
    #status { min-height:22px; margin-top:14px; color:var(--muted); } #status.error { color:#ff9da4; } #status.ok { color:#9aebbd; }
    .grid { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:12px; } .metric { background:#171222; border-radius:12px; padding:13px; } .metric span { display:block; color:var(--muted); font-size:12px; } .metric strong { display:block; margin-top:5px; color:var(--gold); font-size:18px; }
    table { width:100%; margin-top:14px; border-collapse:collapse; } th,td { padding:10px 6px; text-align:left; border-bottom:1px solid #3d334c; } th { color:var(--muted); font-size:12px; font-weight:600; }
    .notice { border-left:3px solid var(--gold); color:#e8ddf0; padding:8px 12px; background:#292136; border-radius:7px; line-height:1.55; font-size:13px; margin-top:14px; }
    .switch { display:flex; align-items:center; justify-content:space-between; gap:10px; min-height:48px; padding:11px 12px; background:#171222; border:1px solid #3d334c; border-radius:11px; color:#e8ddf0; } .switch input { width:20px; height:20px; accent-color:var(--accent); }
    @media (max-width:600px) { .grid { grid-template-columns:1fr; } main { width:min(100% - 20px,800px); margin:18px auto; } }
  </style>
</head>
<body><main>
  <h1>JoyeDrama 奖励运营后台</h1>
  <p class="intro">此页面仅调整未来生成的运营规则。已领取的随机红包、已完成的单集奖励和已达成的皇冠奖励不会回溯变更。</p>
  <section class="card">
    <div class="notice">设置 <code>ADMIN_DASHBOARD_TOKEN</code> 后使用。令牌只保存在当前浏览器会话中，并通过请求头验证，不写入地址栏或数据库。</div>
    <label for="token">管理员令牌</label><input id="token" type="password" autocomplete="off" placeholder="粘贴 ADMIN_DASHBOARD_TOKEN">
    <button class="secondary" id="load">读取当前规则</button><div id="status"></div>
  </section>
  <section class="card" id="policy" hidden>
    <h2>单集奖励与解锁</h2>
    <div class="notice">每次完整观看激励广告可连续解锁指定集数。关闭“单集广告翻倍”后，播放器不显示翻倍入口，避免该广告位打破低频广告设计。</div>
    <div class="grid">
      <div><label for="episodeRewardGems">单集基础现金奖励单位（默认 6,000 = US$0.60）</label><input id="episodeRewardGems" type="number" min="0" step="100"></div>
      <div><label for="episodesPerRewardedUnlock">每条激励广告连续解锁集数</label><input id="episodesPerRewardedUnlock" type="number" min="1" max="100" step="1"></div>
      <label class="switch">启用单集广告翻倍<input id="episodeRewardBoostEnabled" type="checkbox"></label>
    </div>

    <h2>追剧任务随机红包</h2>
    <div class="notice">随机宝箱已取消。用户完成追剧任务节点后，完整观看一条激励广告领取一笔随机现金红包；18 集之后按设定间隔持续生成节点，可按需将任务线扩展到 10,000 集。</div>
    <div class="grid">
      <div><label for="taskMaxEpisode">追剧任务线最大集数</label><input id="taskMaxEpisode" type="number" min="18" max="10000" step="1"></div>
      <div><label for="taskInterval">18 集后任务节点间隔</label><input id="taskInterval" type="number" min="1" max="1000" step="1"></div>
      <div><label for="taskRedMin">追剧任务红包最小内部单位</label><input id="taskRedMin" type="number" min="0" step="100"></div>
      <div><label for="taskRedMax">追剧任务红包最大内部单位</label><input id="taskRedMax" type="number" min="0" step="100"></div>
    </div>

    <h2 style="margin-top:25px">插屏广告规则</h2>
    <div class="grid" style="margin-top:12px">
      <label class="switch">兼容旧客户端的领取插屏开关（v7 新客户端改用激励广告）<input id="rewardClaimEnabled" type="checkbox"></label>
      <label class="switch">兼容旧客户端的下一集插屏开关（v7 新客户端改用激励广告）<input id="nextEpisodeEnabled" type="checkbox"></label>
      <label class="switch">剧中插屏<input id="midEpisodeEnabled" type="checkbox"></label>
      <label class="switch">拖拽进度条插屏<input id="seekEnabled" type="checkbox"></label>
      <label class="switch">调整播放速度插屏<input id="speedEnabled" type="checkbox"></label>
      <label class="switch">横向切换剧集插屏<input id="dramaSwitchEnabled" type="checkbox"></label>
      <label class="switch">激励广告加载失败时回退插屏<input id="rewardedFallbackEnabled" type="checkbox"></label>
      <div><label for="midEpisodeSeconds">首次累计观看时长触发剧中插屏（秒）</label><input id="midEpisodeSeconds" type="number" min="0" max="3600" step="1"></div>
      <div><label for="midEpisodeRepeatSeconds">后续剧中插屏间隔（秒）</label><input id="midEpisodeRepeatSeconds" type="number" min="0" max="3600" step="1"></div>
      <div><label for="interstitialCooldownSeconds">插屏冷却 CD（秒）</label><input id="interstitialCooldownSeconds" type="number" min="0" max="3600" step="1"></div>
      <div><label for="rewardedSkipThreshold">跳过多少次激励广告后允许插屏（0=每次）</label><input id="rewardedSkipThreshold" type="number" min="0" max="50" step="1"></div>
      <div><label for="midEpisodeLimit">兼容旧规则的剧中插屏每小时上限（0=关闭）</label><input id="midEpisodeLimit" type="number" min="0" max="120" step="1"></div>
      <div><label for="globalInterstitialLimit">兼容旧规则的所有插屏每小时上限（0=关闭）</label><input id="globalInterstitialLimit" type="number" min="0" max="120" step="1"></div>
      <div><label for="seekLimit">兼容旧规则的拖拽插屏每小时上限（0=关闭）</label><input id="seekLimit" type="number" min="0" max="120" step="1"></div>
      <div><label for="speedLimit">兼容旧规则的倍速插屏每小时上限（0=关闭）</label><input id="speedLimit" type="number" min="0" max="240" step="1"></div>
      <div><label for="actionRewardThreshold">兼容旧规则的待领取奖励阈值（新客户端不使用）</label><input id="actionRewardThreshold" type="number" min="0" max="1000000000" step="100"></div>
      <div><label for="reviewRewardGems">五星好评一次性现金奖励单位</label><input id="reviewRewardGems" type="number" min="0" max="10000000000" step="1000"></div>
      <div><label for="episodeInterstitialLimit">每集插屏总上限（0=不限制）</label><input id="episodeInterstitialLimit" type="number" min="0" max="10" step="1"></div>
      <div><label for="dramaSwitchEveryCount">横滑切剧每多少次展示 1 次插屏</label><input id="dramaSwitchEveryCount" type="number" min="1" max="50" step="1"></div>
      <div><label for="dramaSwitchLimit">横滑切剧插屏每小时上限（0=不限制）</label><input id="dramaSwitchLimit" type="number" min="0" max="120" step="1"></div>
    </div>

    <h2 style="margin-top:25px">皇冠与钱包广告成长</h2>
    <div class="notice">只有用户在皇冠之路中主动观看的激励广告才计入成长；每完成指定数量可按 1 集有效追剧计入皇冠成长。钱包广告加速每次额外发放一笔随机现金红包。</div>
    <div class="grid">
      <div><label for="crownAdsPerEpisode">皇冠：多少条激励广告抵 1 集追剧</label><input id="crownAdsPerEpisode" type="number" min="1" max="10" step="1"></div>
      <div><label for="crownRewardedDailyLimit">皇冠主动激励广告每日上限（0=不限制）</label><input id="crownRewardedDailyLimit" type="number" min="0" max="100" step="1"></div>
      <div><label for="walletRedMin">钱包红包最小内部单位</label><input id="walletRedMin" type="number" min="0" step="100"></div>
      <div><label for="walletRedMax">钱包红包最大内部单位</label><input id="walletRedMax" type="number" min="0" step="100"></div>
      <div><label for="releaseBoostLimit">钱包广告加速每日次数</label><input id="releaseBoostLimit" type="number" min="0" max="100" step="1"></div>
      <div><label for="rvDailyLimit">激励广告每日次数（0=不限次数）</label><input id="rvDailyLimit" type="number" min="0" max="1000" step="1"></div>
    </div>

    <h2 style="margin-top:25px">全渠道现金奖励阻尼与总额上限</h2>
    <div class="notice">欢迎、签到、分集追剧、任务红包、钱包红包、皇冠达成奖励均按结算时的累计奖励进度适用同一六档阻尼。用户界面只展示当地货币现金；阻尼参数仅用于控制后续现金奖励发放。</div>
    <div class="grid">
      <div><label for="gemsCap">用户累计奖励进度上限（0=不限制）</label><input id="gemsCap" type="number" min="0" step="1000"></div>
      <div><label for="dampingTier1">第一档最大累计奖励进度</label><input id="dampingTier1" type="number" min="0" step="1000"></div>
      <div><label for="dampingTier2">第二档最大累计奖励进度</label><input id="dampingTier2" type="number" min="0" step="1000"></div>
      <div><label for="dampingTier3">第三档最大累计奖励进度</label><input id="dampingTier3" type="number" min="0" step="1000"></div>
      <div><label for="dampingTier4">第四档最大累计奖励进度</label><input id="dampingTier4" type="number" min="0" step="1000"></div>
      <div><label for="dampingTier5">第五档最大累计奖励进度</label><input id="dampingTier5" type="number" min="0" step="1000"></div>
      <div><label for="dampingM1">第一档所有来源奖励系数</label><input id="dampingM1" type="number" min="0" max="1" step="0.01"></div>
      <div><label for="dampingM2">第二档所有来源奖励系数</label><input id="dampingM2" type="number" min="0" max="1" step="0.01"></div>
      <div><label for="dampingM3">第三档所有来源奖励系数</label><input id="dampingM3" type="number" min="0" max="1" step="0.01"></div>
      <div><label for="dampingM4">第四档所有来源奖励系数</label><input id="dampingM4" type="number" min="0" max="1" step="0.01"></div>
      <div><label for="dampingM5">第五档所有来源奖励系数</label><input id="dampingM5" type="number" min="0" max="1" step="0.01"></div>
      <div><label for="dampingM6">第六档所有来源奖励系数</label><input id="dampingM6" type="number" min="0" max="1" step="0.01"></div>
    </div>
    <button id="saveOperating">保存随机红包与广告规则</button>

    <h2 style="margin-top:25px">区域现金钱包策略</h2>
    <div class="notice">金额字段均使用“当地货币 × 1,000,000”的微单位：例如 USD 1.00 填 1000000，BRL 3.00 填 3000000，IDR 10000 填 10000000000。未匹配地区统一采用 DEFAULT/USD；已产生资产的用户不会自动切换币种，以避免账本混币。</div>
    <button class="secondary" id="newMarket">新增地区钱包策略</button>
    <div id="marketCreate" class="notice" hidden>
      <strong>新增地区</strong><div class="grid">
        <div><label for="new-market-code">地区代码（如 MX、TH）</label><input id="new-market-code" maxlength="16" placeholder="MX"></div>
        <div><label for="new-currency-code">货币代码</label><input id="new-currency-code" maxlength="3" placeholder="MXN"></div>
        <div><label for="new-currency-symbol">货币符号</label><input id="new-currency-symbol" maxlength="8" placeholder="$"></div>
        <div><label for="new-currency-digits">小数位</label><input id="new-currency-digits" type="number" min="0" max="6" value="2"></div>
        <div><label for="new-market-local">每 10,000 Gems 本币微单位</label><input id="new-market-local" type="number" min="1" value="1000000"></div>
        <div><label for="new-market-usd">每 10,000 Gems 美元参考微单位</label><input id="new-market-usd" type="number" min="1" value="1000000"></div>
        <div><label for="new-market-threshold">提现门槛微单位</label><input id="new-market-threshold" type="number" min="1" value="1000000"></div>
        <div><label for="new-market-welcome">欢迎奖励 Gems</label><input id="new-market-welcome" type="number" min="0" value="200000"></div>
        <div><label for="new-market-release">每日转入微单位</label><input id="new-market-release" type="number" min="1" value="1"></div>
      </div><button id="createMarket">保存新增地区</button><button class="secondary" id="cancelMarket">取消</button>
    </div>
    <table><thead><tr><th>市场</th><th>币种 / 符号</th><th>每 10,000 Gems 本币微单位</th><th>每 10,000 Gems 美元参考微单位</th><th>启用</th><th>操作</th></tr></thead><tbody id="marketWalletRows"></tbody></table>

    <h2 style="margin-top:25px">皇冠等级单集宝石倍率</h2>
    <div class="notice">新手默认 1.00 倍；用户达成皇冠等级后，后续每集生成的待领取宝石按其当前最高等级倍率计算。金额已经生成的分集奖励不会变更。</div>
    <table><thead><tr><th>等级</th><th>等级倍率</th><th>操作</th></tr></thead><tbody id="crowns"></tbody></table>
  </section>
</main>
<script>
const $=id=>document.getElementById(id); let current=null;
function auth(){return {'Authorization':'Bearer '+$('token').value.trim(),'Content-Type':'application/json'};}
function status(text,type=''){const e=$('status');e.textContent=text;e.className=type;}
function bool(id,value){$(id).checked=Boolean(value);}
function number(id,value){$(id).value=Number(value);}
function render(p){
  current=p; $('policy').hidden=false;
  number('episodeRewardGems',p.episode_reward_gems); number('episodesPerRewardedUnlock',p.episodes_per_rewarded_unlock); bool('episodeRewardBoostEnabled',p.episode_reward_boost_enabled);
  number('taskMaxEpisode',p.episode_task_max_episode); number('taskInterval',p.episode_task_post18_interval); number('taskRedMin',p.episode_task_redpacket_min_gems); number('taskRedMax',p.episode_task_redpacket_max_gems);
  bool('rewardClaimEnabled',p.reward_claim_interstitial_enabled); bool('nextEpisodeEnabled',p.next_episode_interstitial_enabled); bool('midEpisodeEnabled',p.mid_episode_interstitial_enabled); bool('seekEnabled',p.seek_interstitial_enabled); bool('speedEnabled',p.speed_interstitial_enabled); bool('dramaSwitchEnabled',p.drama_switch_interstitial_enabled); bool('rewardedFallbackEnabled',p.rewarded_fallback_interstitial_enabled);
  number('midEpisodeSeconds',p.mid_episode_interstitial_trigger_seconds); number('midEpisodeRepeatSeconds',p.mid_episode_interstitial_repeat_seconds); number('interstitialCooldownSeconds',p.interstitial_cooldown_seconds); number('rewardedSkipThreshold',p.interstitial_rewarded_skip_threshold); number('midEpisodeLimit',p.mid_episode_interstitial_hourly_limit); number('globalInterstitialLimit',p.interstitial_global_hourly_limit); number('seekLimit',p.seek_interstitial_hourly_limit); number('speedLimit',p.speed_interstitial_hourly_limit); number('actionRewardThreshold',p.action_interstitial_reward_threshold_gems); number('reviewRewardGems',p.review_reward_gems); number('episodeInterstitialLimit',p.episode_interstitial_limit); number('dramaSwitchEveryCount',p.drama_switch_interstitial_every_count); number('dramaSwitchLimit',p.drama_switch_interstitial_hourly_limit);
  number('crownAdsPerEpisode',p.crown_rewarded_ads_per_episode); number('crownRewardedDailyLimit',p.crown_rewarded_daily_limit); number('walletRedMin',p.wallet_redpacket_min_gems); number('walletRedMax',p.wallet_redpacket_max_gems); number('releaseBoostLimit',p.release_boost_daily_limit); number('rvDailyLimit',p.rv_daily_limit);
  number('gemsCap',p.gems_earned_cap); number('dampingTier1',p.gem_damping_tier1_threshold); number('dampingTier2',p.gem_damping_tier2_threshold); number('dampingTier3',p.gem_damping_tier3_threshold); number('dampingTier4',p.gem_damping_tier4_threshold); number('dampingTier5',p.gem_damping_tier5_threshold); number('dampingM1',p.gem_damping_tier1_multiplier); number('dampingM2',p.gem_damping_tier2_multiplier); number('dampingM3',p.gem_damping_tier3_multiplier); number('dampingM4',p.gem_damping_tier4_multiplier); number('dampingM5',p.gem_damping_tier5_multiplier); number('dampingM6',p.gem_damping_tier6_multiplier);
  $('marketWalletRows').innerHTML=(p.market_wallet_policies||[]).map(m=>`<tr><td><strong>${m.market_code}</strong><input id="market-code-${m.market_code}" type="hidden" value="${m.market_code}"></td><td><input id="market-currency-${m.market_code}" maxlength="3" value="${m.currency_code}"><input id="market-symbol-${m.market_code}" value="${m.currency_symbol}"><input id="market-digits-${m.market_code}" type="number" min="0" max="6" value="${m.currency_fraction_digits}"></td><td><input id="market-local-${m.market_code}" type="number" min="1" step="1" value="${m.currency_micros_per_10000_gems}"></td><td><input id="market-usd-${m.market_code}" type="number" min="1" step="1" value="${m.usd_micros_per_10000_gems}"></td><td><input id="market-enabled-${m.market_code}" type="checkbox" ${m.enabled!==false?'checked':''} ${m.market_code==='DEFAULT'?'disabled':''}></td><td><button class="secondary" data-market="${m.market_code}">保存</button></td></tr><tr><td colspan="6"><small>提现门槛微单位：<input id="market-threshold-${m.market_code}" type="number" min="1" step="1" value="${m.withdrawal_threshold_micros}">　欢迎 Gems：<input id="market-welcome-${m.market_code}" type="number" min="0" step="1" value="${m.welcome_bonus_gems}">　每日转入微单位：<input id="market-release-${m.market_code}" type="number" min="1" step="1" value="${m.daily_release_micros}"></small></td></tr>`).join('');
  document.querySelectorAll('[data-market]').forEach(btn=>btn.onclick=()=>saveMarketWallet(btn.dataset.market));
  $('crowns').innerHTML=(p.crown_multipliers||[]).map(c=>`<tr><td>${c.title}</td><td><input id="crown-${c.code}" type="number" min="1" max="10" step="0.01" value="${c.episode_reward_multiplier}"></td><td><button class="secondary" data-crown="${c.code}">保存倍率</button></td></tr>`).join('');
  document.querySelectorAll('[data-crown]').forEach(btn=>btn.onclick=()=>saveCrown(btn.dataset.crown));
}
async function request(url,body){const r=await fetch(url,{method:'PUT',headers:auth(),body:JSON.stringify(body)});const j=await r.json();if(!r.ok)throw Error(j.msg||'保存失败');return j.data;}
async function load(){status('正在读取规则…');try{const r=await fetch('/v2/admin/policy',{headers:auth()});const j=await r.json();if(!r.ok)throw Error(j.msg||'读取失败');render(j.data);status('规则已加载。','ok');}catch(e){status(e.message,'error');}}
async function saveOperating(){
  const body={episode_reward_gems:Number($('episodeRewardGems').value),episodes_per_rewarded_unlock:Number($('episodesPerRewardedUnlock').value),episode_reward_boost_enabled:$('episodeRewardBoostEnabled').checked,episode_task_max_episode:Number($('taskMaxEpisode').value),episode_task_post18_interval:Number($('taskInterval').value),episode_task_redpacket_min_gems:Number($('taskRedMin').value),episode_task_redpacket_max_gems:Number($('taskRedMax').value),wallet_redpacket_min_gems:Number($('walletRedMin').value),wallet_redpacket_max_gems:Number($('walletRedMax').value),reward_claim_interstitial_enabled:$('rewardClaimEnabled').checked,next_episode_interstitial_enabled:$('nextEpisodeEnabled').checked,mid_episode_interstitial_enabled:$('midEpisodeEnabled').checked,mid_episode_interstitial_trigger_seconds:Number($('midEpisodeSeconds').value),mid_episode_interstitial_repeat_seconds:Number($('midEpisodeRepeatSeconds').value),interstitial_cooldown_seconds:Number($('interstitialCooldownSeconds').value),interstitial_rewarded_skip_threshold:Number($('rewardedSkipThreshold').value),mid_episode_interstitial_hourly_limit:Number($('midEpisodeLimit').value),interstitial_global_hourly_limit:Number($('globalInterstitialLimit').value),seek_interstitial_enabled:$('seekEnabled').checked,seek_interstitial_hourly_limit:Number($('seekLimit').value),speed_interstitial_enabled:$('speedEnabled').checked,speed_interstitial_hourly_limit:Number($('speedLimit').value),action_interstitial_reward_threshold_gems:Number($('actionRewardThreshold').value),review_reward_gems:Number($('reviewRewardGems').value),episode_interstitial_limit:Number($('episodeInterstitialLimit').value),drama_switch_interstitial_enabled:$('dramaSwitchEnabled').checked,drama_switch_interstitial_every_count:Number($('dramaSwitchEveryCount').value),drama_switch_interstitial_hourly_limit:Number($('dramaSwitchLimit').value),rewarded_fallback_interstitial_enabled:$('rewardedFallbackEnabled').checked,crown_rewarded_ads_per_episode:Number($('crownAdsPerEpisode').value),crown_rewarded_daily_limit:Number($('crownRewardedDailyLimit').value),release_boost_daily_limit:Number($('releaseBoostLimit').value),rv_daily_limit:Number($('rvDailyLimit').value),gems_earned_cap:Number($('gemsCap').value),gem_damping_tier1_threshold:Number($('dampingTier1').value),gem_damping_tier2_threshold:Number($('dampingTier2').value),gem_damping_tier3_threshold:Number($('dampingTier3').value),gem_damping_tier4_threshold:Number($('dampingTier4').value),gem_damping_tier5_threshold:Number($('dampingTier5').value),gem_damping_tier1_multiplier:Number($('dampingM1').value),gem_damping_tier2_multiplier:Number($('dampingM2').value),gem_damping_tier3_multiplier:Number($('dampingM3').value),gem_damping_tier4_multiplier:Number($('dampingM4').value),gem_damping_tier5_multiplier:Number($('dampingM5').value),gem_damping_tier6_multiplier:Number($('dampingM6').value)};
  if(body.episode_task_redpacket_min_gems>body.episode_task_redpacket_max_gems||body.wallet_redpacket_min_gems>body.wallet_redpacket_max_gems){status('随机红包最小奖励不能大于最大奖励。','error');return;}
  status('正在保存…');try{render(await request('/v2/admin/policy/operating',body));status('随机红包与广告规则已保存。','ok');}catch(e){status(e.message,'error');}
}
async function saveMarketWallet(market){const body={market_code:market,currency_code:$('market-currency-'+market).value.trim().toUpperCase(),currency_symbol:$('market-symbol-'+market).value.trim(),currency_fraction_digits:Number($('market-digits-'+market).value),currency_micros_per_10000_gems:Number($('market-local-'+market).value),usd_micros_per_10000_gems:Number($('market-usd-'+market).value),withdrawal_threshold_micros:Number($('market-threshold-'+market).value),welcome_bonus_gems:Number($('market-welcome-'+market).value),daily_release_micros:Number($('market-release-'+market).value),enabled:market==='DEFAULT'?true:$('market-enabled-'+market).checked};if(!body.currency_code||!body.currency_symbol||Object.values(body).some(v=>typeof v==='number'&&(!Number.isFinite(v)||v<0))){status('请完整填写有效的区域钱包参数。','error');return;}status('正在保存区域钱包策略…');try{await request('/v2/admin/policy/market-wallet',body);await load();status(market+' 区域钱包策略已保存。','ok');}catch(e){status(e.message,'error');}}
async function saveCrown(code){const value=Number($('crown-'+code).value);if(!Number.isFinite(value)||value<1||value>10){status('皇冠倍率必须在 1.00 到 10.00 之间。','error');return;}status('正在保存皇冠倍率…');try{render(await request('/v2/admin/crown-multiplier',{code,episode_reward_multiplier:value}));status(code+' 单集宝石倍率已保存。','ok');}catch(e){status(e.message,'error');}}
async function createMarket(){const body={market_code:$('new-market-code').value.trim().toUpperCase(),currency_code:$('new-currency-code').value.trim().toUpperCase(),currency_symbol:$('new-currency-symbol').value.trim(),currency_fraction_digits:Number($('new-currency-digits').value),currency_micros_per_10000_gems:Number($('new-market-local').value),usd_micros_per_10000_gems:Number($('new-market-usd').value),withdrawal_threshold_micros:Number($('new-market-threshold').value),welcome_bonus_gems:Number($('new-market-welcome').value),daily_release_micros:Number($('new-market-release').value),enabled:true};if(!body.market_code||!body.currency_code||!body.currency_symbol){status('请填写地区代码、货币代码和货币符号。','error');return;}status('正在新增区域钱包策略…');try{await request('/v2/admin/policy/market-wallet',body);$('marketCreate').hidden=true;await load();status(body.market_code+' 区域钱包策略已新增。','ok');}catch(e){status(e.message,'error');}}
$('newMarket').onclick=()=>{$('marketCreate').hidden=false;};$('cancelMarket').onclick=()=>{$('marketCreate').hidden=true;};$('createMarket').onclick=createMarket;$('load').onclick=load;$('saveOperating').onclick=saveOperating;
</script></body></html>"""
