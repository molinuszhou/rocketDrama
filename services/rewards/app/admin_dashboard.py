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
  <p class="intro">此页面仅调整未来生成的运营规则。已产生的随机宝箱、已完成的单集奖励和已达成的皇冠奖励不会回溯变更。</p>
  <section class="card">
    <div class="notice">设置 <code>ADMIN_DASHBOARD_TOKEN</code> 后使用。令牌只保存在当前浏览器会话中，并通过请求头验证，不写入地址栏或数据库。</div>
    <label for="token">管理员令牌</label><input id="token" type="password" autocomplete="off" placeholder="粘贴 ADMIN_DASHBOARD_TOKEN">
    <button class="secondary" id="load">读取当前规则</button><div id="status"></div>
  </section>
  <section class="card" id="policy" hidden>
    <h2>随机宝箱</h2>
    <div class="notice">用户每日自动获得的宝箱与每累计指定集数获得的宝箱均可连续开启；每只宝箱都需要完整观看一条激励广告，具体金额在服务端随机生成。</div>
    <div class="grid">
      <div><label for="chestMin">最小宝石</label><input id="chestMin" type="number" min="0" step="1"></div>
      <div><label for="chestMax">最大宝石</label><input id="chestMax" type="number" min="0" step="1"></div>
      <div><label for="dailyChestCount">每日自动宝箱数</label><input id="dailyChestCount" type="number" min="0" max="10" step="1"></div>
      <div><label for="episodesPerChest">每多少集获得 1 个宝箱</label><input id="episodesPerChest" type="number" min="1" max="100" step="1"></div>
    </div>

    <h2 style="margin-top:25px">插屏广告规则</h2>
    <div class="grid" style="margin-top:12px">
      <label class="switch">兼容旧客户端的领取插屏开关（v7 新客户端改用激励广告）<input id="rewardClaimEnabled" type="checkbox"></label>
      <label class="switch">兼容旧客户端的下一集插屏开关（v7 新客户端改用激励广告）<input id="nextEpisodeEnabled" type="checkbox"></label>
      <label class="switch">每集剧中插屏<input id="midEpisodeEnabled" type="checkbox"></label>
      <label class="switch">拖拽进度条插屏<input id="seekEnabled" type="checkbox"></label>
      <label class="switch">调整播放速度插屏<input id="speedEnabled" type="checkbox"></label>
      <label class="switch">横向切换剧集插屏<input id="dramaSwitchEnabled" type="checkbox"></label>
      <label class="switch">激励广告加载失败时回退插屏<input id="rewardedFallbackEnabled" type="checkbox"></label>
      <div><label for="midEpisodeSeconds">剧中插屏触发秒数</label><input id="midEpisodeSeconds" type="number" min="0" max="3600" step="1"></div>
      <div><label for="midEpisodeLimit">剧中插屏每小时上限</label><input id="midEpisodeLimit" type="number" min="0" max="120" step="1"></div>
      <div><label for="seekLimit">拖拽插屏每小时上限（0=不限制）</label><input id="seekLimit" type="number" min="0" max="120" step="1"></div>
      <div><label for="speedLimit">倍速插屏每小时上限（0=不限制）</label><input id="speedLimit" type="number" min="0" max="240" step="1"></div>
      <div><label for="actionRewardThreshold">拖动/倍速触发所需待领取宝石</label><input id="actionRewardThreshold" type="number" min="0" max="1000000000" step="100"></div>
      <div><label for="episodeInterstitialLimit">每集插屏总上限（0=不限制）</label><input id="episodeInterstitialLimit" type="number" min="0" max="10" step="1"></div>
      <div><label for="dramaSwitchEveryCount">横滑切剧每多少次展示 1 次插屏</label><input id="dramaSwitchEveryCount" type="number" min="1" max="50" step="1"></div>
      <div><label for="dramaSwitchLimit">横滑切剧插屏每小时上限（0=不限制）</label><input id="dramaSwitchLimit" type="number" min="0" max="120" step="1"></div>
    </div>

    <h2 style="margin-top:25px">皇冠与钱包广告成长</h2>
    <div class="notice">每完成指定数量的完整激励广告，可按 1 集有效追剧计入皇冠成长；钱包广告加速每次额外转入金额按当日基础可提现转入的百分比计算。</div>
    <div class="grid">
      <div><label for="crownAdsPerEpisode">皇冠：多少条激励广告抵 1 集追剧</label><input id="crownAdsPerEpisode" type="number" min="1" max="10" step="1"></div>
      <div><label for="releaseBoostPercent">钱包广告加速比例（0.20 = 20%）</label><input id="releaseBoostPercent" type="number" min="0.01" max="1" step="0.01"></div>
      <div><label for="releaseBoostLimit">钱包广告加速每日次数</label><input id="releaseBoostLimit" type="number" min="0" max="100" step="1"></div>
      <div><label for="rvDailyLimit">激励广告每日次数（0=不限次数）</label><input id="rvDailyLimit" type="number" min="0" max="1000" step="1"></div>
    </div>

    <h2 style="margin-top:25px">全渠道宝石阻尼与总额上限</h2>
    <div class="notice">欢迎、签到、分集追剧与翻倍、随机宝箱、皇冠达成奖励均按结算时的累计 Gems 适用同一四档阻尼。每个阈值是当前档的最大累计 Gems，也是下一档开始生效的边界；总额达到上限后不再增加宝石。</div>
    <div class="grid">
      <div><label for="gemsCap">用户累计宝石上限（0=不限制）</label><input id="gemsCap" type="number" min="0" step="1000"></div>
      <div><label for="dampingTier1">第一档最大累计 Gems</label><input id="dampingTier1" type="number" min="0" step="1000"></div>
      <div><label for="dampingTier2">第二档最大累计 Gems</label><input id="dampingTier2" type="number" min="0" step="1000"></div>
      <div><label for="dampingTier3">第三档最大累计 Gems</label><input id="dampingTier3" type="number" min="0" step="1000"></div>
      <div><label for="dampingM1">第一档所有来源奖励系数</label><input id="dampingM1" type="number" min="0" max="1" step="0.01"></div>
      <div><label for="dampingM2">第二档所有来源奖励系数</label><input id="dampingM2" type="number" min="0" max="1" step="0.01"></div>
      <div><label for="dampingM3">第三档所有来源奖励系数</label><input id="dampingM3" type="number" min="0" max="1" step="0.01"></div>
      <div><label for="dampingM4">第四档所有来源奖励系数</label><input id="dampingM4" type="number" min="0" max="1" step="0.01"></div>
    </div>
    <button id="saveOperating">保存随机宝箱与广告规则</button>

    <h2 style="margin-top:25px">区域钱包与兑换策略</h2>
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
  number('chestMin',p.random_chest_min_gems); number('chestMax',p.random_chest_max_gems); number('dailyChestCount',p.daily_random_chest_count); number('episodesPerChest',p.episodes_per_random_chest);
  bool('rewardClaimEnabled',p.reward_claim_interstitial_enabled); bool('nextEpisodeEnabled',p.next_episode_interstitial_enabled); bool('midEpisodeEnabled',p.mid_episode_interstitial_enabled); bool('seekEnabled',p.seek_interstitial_enabled); bool('speedEnabled',p.speed_interstitial_enabled); bool('dramaSwitchEnabled',p.drama_switch_interstitial_enabled); bool('rewardedFallbackEnabled',p.rewarded_fallback_interstitial_enabled);
  number('midEpisodeSeconds',p.mid_episode_interstitial_trigger_seconds); number('midEpisodeLimit',p.mid_episode_interstitial_hourly_limit); number('seekLimit',p.seek_interstitial_hourly_limit); number('speedLimit',p.speed_interstitial_hourly_limit); number('actionRewardThreshold',p.action_interstitial_reward_threshold_gems); number('episodeInterstitialLimit',p.episode_interstitial_limit); number('dramaSwitchEveryCount',p.drama_switch_interstitial_every_count); number('dramaSwitchLimit',p.drama_switch_interstitial_hourly_limit);
  number('crownAdsPerEpisode',p.crown_rewarded_ads_per_episode); number('releaseBoostPercent',p.release_boost_percent); number('releaseBoostLimit',p.release_boost_daily_limit); number('rvDailyLimit',p.rv_daily_limit);
  number('gemsCap',p.gems_earned_cap); number('dampingTier1',p.gem_damping_tier1_threshold); number('dampingTier2',p.gem_damping_tier2_threshold); number('dampingTier3',p.gem_damping_tier3_threshold); number('dampingM1',p.gem_damping_tier1_multiplier); number('dampingM2',p.gem_damping_tier2_multiplier); number('dampingM3',p.gem_damping_tier3_multiplier); number('dampingM4',p.gem_damping_tier4_multiplier);
  $('marketWalletRows').innerHTML=(p.market_wallet_policies||[]).map(m=>`<tr><td><strong>${m.market_code}</strong><input id="market-code-${m.market_code}" type="hidden" value="${m.market_code}"></td><td><input id="market-currency-${m.market_code}" maxlength="3" value="${m.currency_code}"><input id="market-symbol-${m.market_code}" value="${m.currency_symbol}"><input id="market-digits-${m.market_code}" type="number" min="0" max="6" value="${m.currency_fraction_digits}"></td><td><input id="market-local-${m.market_code}" type="number" min="1" step="1" value="${m.currency_micros_per_10000_gems}"></td><td><input id="market-usd-${m.market_code}" type="number" min="1" step="1" value="${m.usd_micros_per_10000_gems}"></td><td><input id="market-enabled-${m.market_code}" type="checkbox" ${m.enabled!==false?'checked':''} ${m.market_code==='DEFAULT'?'disabled':''}></td><td><button class="secondary" data-market="${m.market_code}">保存</button></td></tr><tr><td colspan="6"><small>提现门槛微单位：<input id="market-threshold-${m.market_code}" type="number" min="1" step="1" value="${m.withdrawal_threshold_micros}">　欢迎 Gems：<input id="market-welcome-${m.market_code}" type="number" min="0" step="1" value="${m.welcome_bonus_gems}">　每日转入微单位：<input id="market-release-${m.market_code}" type="number" min="1" step="1" value="${m.daily_release_micros}"></small></td></tr>`).join('');
  document.querySelectorAll('[data-market]').forEach(btn=>btn.onclick=()=>saveMarketWallet(btn.dataset.market));
  $('crowns').innerHTML=(p.crown_multipliers||[]).map(c=>`<tr><td>${c.title}</td><td><input id="crown-${c.code}" type="number" min="1" max="10" step="0.01" value="${c.episode_reward_multiplier}"></td><td><button class="secondary" data-crown="${c.code}">保存倍率</button></td></tr>`).join('');
  document.querySelectorAll('[data-crown]').forEach(btn=>btn.onclick=()=>saveCrown(btn.dataset.crown));
}
async function request(url,body){const r=await fetch(url,{method:'PUT',headers:auth(),body:JSON.stringify(body)});const j=await r.json();if(!r.ok)throw Error(j.msg||'保存失败');return j.data;}
async function load(){status('正在读取规则…');try{const r=await fetch('/v2/admin/policy',{headers:auth()});const j=await r.json();if(!r.ok)throw Error(j.msg||'读取失败');render(j.data);status('规则已加载。','ok');}catch(e){status(e.message,'error');}}
async function saveOperating(){
  const body={random_chest_min_gems:Number($('chestMin').value),random_chest_max_gems:Number($('chestMax').value),daily_random_chest_count:Number($('dailyChestCount').value),episodes_per_random_chest:Number($('episodesPerChest').value),reward_claim_interstitial_enabled:$('rewardClaimEnabled').checked,next_episode_interstitial_enabled:$('nextEpisodeEnabled').checked,mid_episode_interstitial_enabled:$('midEpisodeEnabled').checked,mid_episode_interstitial_trigger_seconds:Number($('midEpisodeSeconds').value),mid_episode_interstitial_hourly_limit:Number($('midEpisodeLimit').value),seek_interstitial_enabled:$('seekEnabled').checked,seek_interstitial_hourly_limit:Number($('seekLimit').value),speed_interstitial_enabled:$('speedEnabled').checked,speed_interstitial_hourly_limit:Number($('speedLimit').value),action_interstitial_reward_threshold_gems:Number($('actionRewardThreshold').value),episode_interstitial_limit:Number($('episodeInterstitialLimit').value),drama_switch_interstitial_enabled:$('dramaSwitchEnabled').checked,drama_switch_interstitial_every_count:Number($('dramaSwitchEveryCount').value),drama_switch_interstitial_hourly_limit:Number($('dramaSwitchLimit').value),rewarded_fallback_interstitial_enabled:$('rewardedFallbackEnabled').checked,crown_rewarded_ads_per_episode:Number($('crownAdsPerEpisode').value),release_boost_percent:Number($('releaseBoostPercent').value),release_boost_daily_limit:Number($('releaseBoostLimit').value),rv_daily_limit:Number($('rvDailyLimit').value),gems_earned_cap:Number($('gemsCap').value),gem_damping_tier1_threshold:Number($('dampingTier1').value),gem_damping_tier2_threshold:Number($('dampingTier2').value),gem_damping_tier3_threshold:Number($('dampingTier3').value),gem_damping_tier1_multiplier:Number($('dampingM1').value),gem_damping_tier2_multiplier:Number($('dampingM2').value),gem_damping_tier3_multiplier:Number($('dampingM3').value),gem_damping_tier4_multiplier:Number($('dampingM4').value)};
  if(body.random_chest_min_gems>body.random_chest_max_gems){status('随机宝箱最小宝石不能大于最大宝石。','error');return;}
  status('正在保存…');try{render(await request('/v2/admin/policy/operating',body));status('随机宝箱与广告规则已保存。','ok');}catch(e){status(e.message,'error');}
}
async function saveMarketWallet(market){const body={market_code:market,currency_code:$('market-currency-'+market).value.trim().toUpperCase(),currency_symbol:$('market-symbol-'+market).value.trim(),currency_fraction_digits:Number($('market-digits-'+market).value),currency_micros_per_10000_gems:Number($('market-local-'+market).value),usd_micros_per_10000_gems:Number($('market-usd-'+market).value),withdrawal_threshold_micros:Number($('market-threshold-'+market).value),welcome_bonus_gems:Number($('market-welcome-'+market).value),daily_release_micros:Number($('market-release-'+market).value),enabled:market==='DEFAULT'?true:$('market-enabled-'+market).checked};if(!body.currency_code||!body.currency_symbol||Object.values(body).some(v=>typeof v==='number'&&(!Number.isFinite(v)||v<0))){status('请完整填写有效的区域钱包参数。','error');return;}status('正在保存区域钱包策略…');try{await request('/v2/admin/policy/market-wallet',body);await load();status(market+' 区域钱包策略已保存。','ok');}catch(e){status(e.message,'error');}}
async function saveCrown(code){const value=Number($('crown-'+code).value);if(!Number.isFinite(value)||value<1||value>10){status('皇冠倍率必须在 1.00 到 10.00 之间。','error');return;}status('正在保存皇冠倍率…');try{render(await request('/v2/admin/crown-multiplier',{code,episode_reward_multiplier:value}));status(code+' 单集宝石倍率已保存。','ok');}catch(e){status(e.message,'error');}}
async function createMarket(){const body={market_code:$('new-market-code').value.trim().toUpperCase(),currency_code:$('new-currency-code').value.trim().toUpperCase(),currency_symbol:$('new-currency-symbol').value.trim(),currency_fraction_digits:Number($('new-currency-digits').value),currency_micros_per_10000_gems:Number($('new-market-local').value),usd_micros_per_10000_gems:Number($('new-market-usd').value),withdrawal_threshold_micros:Number($('new-market-threshold').value),welcome_bonus_gems:Number($('new-market-welcome').value),daily_release_micros:Number($('new-market-release').value),enabled:true};if(!body.market_code||!body.currency_code||!body.currency_symbol){status('请填写地区代码、货币代码和货币符号。','error');return;}status('正在新增区域钱包策略…');try{await request('/v2/admin/policy/market-wallet',body);$('marketCreate').hidden=true;await load();status(body.market_code+' 区域钱包策略已新增。','ok');}catch(e){status(e.message,'error');}}
$('newMarket').onclick=()=>{$('marketCreate').hidden=false;};$('cancelMarket').onclick=()=>{$('marketCreate').hidden=true;};$('createMarket').onclick=createMarket;$('load').onclick=load;$('saveOperating').onclick=saveOperating;
</script></body></html>"""
