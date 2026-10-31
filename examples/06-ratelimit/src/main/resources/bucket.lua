-- 令牌桶：取令牌 + 回填 + 判额度，三步在 Redis 里原子完成
--
-- KEYS[1] 桶的 key，例如 rl:t1:qwen
-- ARGV[1] capacity    桶容量（也是允许的最大突发）
-- ARGV[2] refill      每秒补充多少令牌
-- ARGV[3] now         当前时间（毫秒，由调用方传入）
-- ARGV[4] cost        这次要取几个令牌
--
-- 返回 1 表示通过，0 表示拒绝

local key      = KEYS[1]
local capacity = tonumber(ARGV[1])
local refill   = tonumber(ARGV[2])
local now      = tonumber(ARGV[3])
local cost     = tonumber(ARGV[4])

local data   = redis.call('HMGET', key, 'tokens', 'ts')
local tokens = tonumber(data[1])
local ts     = tonumber(data[2])

if tokens == nil then
  tokens = capacity
  ts = now
end

-- 按流逝的时间回填，但不超过容量
local delta = math.max(0, now - ts) / 1000.0
tokens = math.min(capacity, tokens + delta * refill)

local allowed = 0
if tokens >= cost then
  tokens = tokens - cost
  allowed = 1
end

redis.call('HMSET', key, 'tokens', tokens, 'ts', now)
-- 桶闲置超过一个补充周期就没必要留着了；TTL 不能比「桶从空到满」的时间更短
redis.call('PEXPIRE', key, math.ceil(capacity / refill * 1000) + 60000)

return allowed