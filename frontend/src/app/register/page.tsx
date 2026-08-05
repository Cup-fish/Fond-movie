'use client'

import { useState } from 'react'
import { useRouter } from 'next/navigation'
import { toast } from 'sonner'
import { useUserStore } from '@/store/user'

/**
 * 注册页 — 交易面（浅色模式），与登录页共享设计语言
 */
export default function RegisterPage() {
  const router = useRouter()
  const { registerAsync } = useUserStore()
  const [nick, setNick] = useState('')
  const [account, setAccount] = useState('')
  const [password, setPassword] = useState('')
  const [inviteCode, setInviteCode] = useState('')
  const [loading, setLoading] = useState(false)

  const handleRegister = async () => {
    if (!inviteCode.trim()) return toast.error('邀请码不能为空！')
    if (!nick.trim()) return toast.error('昵称不能为空！')
    if (!account.trim()) return toast.error('用户名不能为空！')
    if (!password) return toast.error('请输入密码！')

    setLoading(true)
    try {
      const result = await registerAsync(account, password, nick, inviteCode)
      if (result.success) {
        toast.success('注册成功，请登录')
        router.replace('/login')
      } else {
        toast.error(result.msg || '注册失败')
      }
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="min-h-screen bg-surface-strong flex items-center justify-center">
      <div className="w-[400px] bg-white border border-hairline-light rounded-lg shadow-card-light p-8">
        <div className="flex items-center justify-center mb-8">
          <div className="w-10 h-10 bg-primary rounded-full flex items-center justify-center mr-2">
            <span className="text-on-primary font-bold text-xl">猫</span>
          </div>
          <span className="text-2xl font-bold tracking-tight text-ink">猫眼电影</span>
        </div>
        <h2 className="text-xl font-medium text-center mb-6 text-ink">注 册</h2>
        <div className="space-y-4">
          <input
            type="text"
            placeholder="邀请码"
            value={inviteCode}
            onChange={(e) => setInviteCode(e.target.value)}
            className="w-full py-3 px-4 border border-hairline-light rounded-md outline-none text-base text-ink placeholder:text-muted focus:border-primary transition-colors"
          />
          <input
            type="text"
            placeholder="昵称"
            value={nick}
            onChange={(e) => setNick(e.target.value)}
            className="w-full py-3 px-4 border border-hairline-light rounded-md outline-none text-base text-ink placeholder:text-muted focus:border-primary transition-colors"
          />
          <input
            type="text"
            placeholder="用户名"
            value={account}
            onChange={(e) => setAccount(e.target.value)}
            className="w-full py-3 px-4 border border-hairline-light rounded-md outline-none text-base text-ink placeholder:text-muted focus:border-primary transition-colors"
          />
          <input
            type="password"
            placeholder="密码"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && handleRegister()}
            className="w-full py-3 px-4 border border-hairline-light rounded-md outline-none text-base text-ink placeholder:text-muted focus:border-primary transition-colors"
          />
          <button
            onClick={handleRegister}
            disabled={loading}
            className="w-full py-3 bg-primary text-on-primary rounded-md text-base font-semibold disabled:opacity-60 hover:bg-primary-active transition-colors pressable"
          >
            {loading ? '注册中...' : '注 册'}
          </button>
          <p className="text-sm text-muted text-center">
            已有账号？
            <span
              className="text-primary cursor-pointer hover:underline ml-1 font-medium"
              onClick={() => router.push('/login')}
            >
              去登录
            </span>
          </p>
          <p className="text-sm text-muted-strong text-center">
            <span className="cursor-pointer hover:text-primary transition-colors" onClick={() => router.push('/')}>
              返回首页
            </span>
          </p>
        </div>
      </div>
    </div>
  )
}
