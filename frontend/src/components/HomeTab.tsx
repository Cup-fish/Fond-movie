'use client'

import { useState, useEffect } from 'react'
import { useRouter } from 'next/navigation'
import { ChevronRight, TrendingUp } from 'lucide-react'
import { motion, useReducedMotion } from 'motion/react'
import MovieCard from '@/components/MovieCard'
import Loading from '@/components/Loading'
import Reveal from '@/components/motion/Reveal'
import CountUp from '@/components/motion/CountUp'
import api from '@/lib/api'
import type { MovieItem, BoxOfficeItem } from '@/types'

// 硬编码的 TOP 100 数据
const TOP100 = [
  { id: 1, title: '我不是药神', score: 9.6 },
  { id: 2, title: '肖申克的救赎', score: 9.5 },
  { id: 3, title: '海上钢琴师', score: 9.3 },
  { id: 4, title: '绿皮书', score: 9.5 },
  { id: 5, title: '霸王别姬', score: 9.4 },
  { id: 6, title: '美丽人生', score: 9.3 },
  { id: 7, title: '这个杀手不太冷', score: 9.6 },
  { id: 8, title: '星际穿越', score: 9.3 },
  { id: 9, title: '泰坦尼克号', score: 9.6 },
  { id: 10, title: '盗梦空间', score: 9.0 },
]

export default function HomeTab() {
  const [hotMovies, setHotMovies] = useState<MovieItem[]>([])
  const [comingMovies, setComingMovies] = useState<MovieItem[]>([])
  const [loading, setLoading] = useState(true)
  const reduce = useReducedMotion()

  // 硬编码的票房数据 (可以后续接真实接口)
  const boxOffice: BoxOfficeItem[] = [
    { id: 1, title: '流浪地球3', amount: 4271.7, unit: '万' },
    { id: 2, title: '烟火人间', amount: 2023.2, unit: '万' },
    { id: 3, title: '平凡英雄', amount: 1568.3, unit: '万' },
    { id: 4, title: '逐光者', amount: 1526.7, unit: '万' },
    { id: 5, title: '长安幻夜', amount: 883.6, unit: '万' },
  ]

  useEffect(() => {
    Promise.all([
      api.getMovieOnInfoList(),
      api.getComingList(),
    ])
      .then(([hotRes, comingRes]) => {
        setHotMovies(hotRes.movieList?.slice(0, 8) || [])
        setComingMovies(comingRes.coming?.slice(0, 8) || [])
      })
      .catch(() => {})
      .finally(() => setLoading(false))
  }, [])

  const router = useRouter()

  if (loading) return <Loading />

  return (
    <div className="flex gap-10">
      {/* LEFT COLUMN: Movies */}
      <div className="flex-1">
        {/* 正在热映 */}
        <Reveal>
          <div className="flex justify-between items-end mb-6">
            <h2 className="text-2xl font-semibold tracking-tight text-body-dark">
              正在热映{' '}
              <span className="font-plex text-primary text-xl ml-1">
                {hotMovies.length}
              </span>
            </h2>
            <a className="flex items-center text-primary hover:underline text-sm cursor-pointer">
              全部 <ChevronRight size={14} />
            </a>
          </div>
        </Reveal>

        <div className="grid grid-cols-4 gap-6">
          {hotMovies.map((movie, i) => (
            <div key={movie.id} className="flex flex-col items-center">
              <MovieCard movie={movie} showButton={false} index={i} />
              <button
                onClick={() => router.push(`/movie-detail?movieId=${movie.id}`)}
                className="w-full max-w-[160px] py-2 bg-primary text-on-primary border border-white rounded-md hover:bg-primary-active transition-all pressable text-sm font-bold -mt-2"
              >
                购票
              </button>
            </div>
          ))}
        </div>

        {/* 即将上映 */}
        <Reveal delay={0.1}>
          <div className="flex justify-between items-end mb-6 mt-4">
            <h2 className="text-2xl font-semibold tracking-tight text-body-dark">
              即将上映{' '}
              <span className="font-plex text-primary text-xl ml-1">
                {comingMovies.length}
              </span>
            </h2>
            <a className="flex items-center text-primary hover:underline text-sm cursor-pointer">
              全部 <ChevronRight size={14} />
            </a>
          </div>
        </Reveal>

        <div className="grid grid-cols-4 gap-6">
          {comingMovies.map((movie, i) => (
            <div key={movie.id} className="flex flex-col items-center">
              <MovieCard movie={movie} showButton={true} index={i} />
            </div>
          ))}
        </div>
      </div>

      {/* RIGHT COLUMN: Sidebar */}
      <div className="w-[360px] shrink-0">
        {/* 今日票房 — markets-table-card 风格 */}
        <Reveal>
          <div className="mb-10">
            <div className="flex justify-between items-center mb-4">
              <h3 className="text-lg font-semibold text-body-dark flex items-center gap-2">
                <TrendingUp size={16} className="text-trading-up" />
                今日票房
              </h3>
            </div>

            {/* 黄黑撞色盒子：黄底 + 黑字 + 白描边（无 glow，DESIGN.md 禁大气光效） */}
            <div className="bg-primary border border-white rounded-xl p-4">
              {/* Top 1 — 黑底高亮行（黄字数字） */}
              <div className="flex gap-3 mb-4 bg-ink p-2.5 rounded-lg border border-ink relative overflow-hidden">
                <div className="absolute top-0 left-0 w-6 h-6 bg-primary text-on-primary flex items-center justify-center text-xs font-bold z-10 rounded-br-md">
                  1
                </div>
                <img
                  src={`https://picsum.photos/seed/box1/60/80`}
                  alt=""
                  className="w-14 h-[72px] object-cover rounded-md border border-white/20"
                />
                <div className="flex flex-col justify-center flex-1">
                  <h4 className="font-semibold text-white">
                    {boxOffice[0].title}
                  </h4>
                  <p className="font-plex text-primary text-base font-bold mt-1.5">
                    {boxOffice[0].amount.toLocaleString()}
                    <span className="text-xs ml-0.5">{boxOffice[0].unit}</span>
                  </p>
                </div>
              </div>

              {/* List 2-5 — 黄底黑字 */}
              <ul className="space-y-3">
                {boxOffice.slice(1).map((item, idx) => (
                  <li
                    key={item.id}
                    className="flex justify-between items-center text-sm"
                  >
                    <div className="flex items-center flex-1 overflow-hidden">
                      <span className="font-plex text-on-primary/70 font-bold mr-3 w-3 text-right">
                        {idx + 2}
                      </span>
                      <span className="truncate font-semibold text-on-primary">{item.title}</span>
                    </div>
                    <span className="font-plex font-bold text-on-primary text-xs">
                      {item.amount.toLocaleString()}
                      {item.unit}
                    </span>
                  </li>
                ))}
              </ul>
            </div>

            {/* Total Box Office — stat-callout 黄色大数字 */}
            <div className="mt-4 bg-surface-elevated border border-hairline-dark rounded-xl p-4 flex justify-between items-center">
              <div>
                <div className="flex items-end">
                  <CountUp
                    value={10273.5}
                    className="text-3xl font-bold text-primary"
                  />
                  <span className="text-sm mb-1 ml-1 text-primary">万</span>
                </div>
                <div className="text-[10px] text-muted mt-1.5 font-plex tracking-wide">
                  猫眼专业版实时票房数据
                </div>
              </div>
              <div className="text-xs text-muted-strong flex items-center cursor-pointer hover:text-primary transition-colors">
                查看更多 <ChevronRight size={12} />
              </div>
            </div>
          </div>
        </Reveal>

        {/* 最受期待 */}
        <Reveal delay={0.05}>
          <div className="mb-10">
            <div className="flex justify-between items-end mb-4">
              <h3 className="text-lg font-semibold text-body-dark">最受期待</h3>
              <a className="flex items-center text-muted-strong hover:text-primary text-xs cursor-pointer transition-colors">
                查看完整榜单 <ChevronRight size={12} />
              </a>
            </div>

            {/* Top 1 Large — 黄底盒子 + 黑底序号 */}
            {comingMovies[0] && (
              <div
                className="mb-4 rounded-xl border border-white bg-primary overflow-hidden group cursor-pointer card-lift"
                onClick={() => router.push(`/movie-detail?movieId=${comingMovies[0].id}`)}
              >
                <div className="w-full h-44 overflow-hidden relative">
                  <img
                    src={comingMovies[0].img}
                    className="w-full object-cover -mt-8 group-hover:scale-105 transition-transform duration-500"
                    alt="Top1"
                  />
                  <div className="absolute top-2 left-2 bg-ink text-primary w-6 h-6 flex items-center justify-center font-bold rounded-md text-sm border border-white/30">
                    1
                  </div>
                  <div className="absolute bottom-0 w-full bg-gradient-to-t from-black/85 to-transparent p-3">
                    <div className="font-semibold text-white">{comingMovies[0].nm}</div>
                    <div className="text-xs text-white/60 mt-0.5">
                      {comingMovies[0].comingTitle || comingMovies[0].pubDesc}
                    </div>
                    <div className="font-plex text-xs text-primary font-bold mt-1">
                      {comingMovies[0].wish}人想看
                    </div>
                  </div>
                </div>
              </div>
            )}

            {/* Row of 2 & 3 — 黄底卡片黑字 */}
            {comingMovies.length >= 3 && (
              <div className="flex gap-3 mb-4">
                {[comingMovies[1], comingMovies[2]].map((m, idx) => (
                  <div
                    key={m.id}
                    className="flex-1 cursor-pointer card-lift"
                    onClick={() => router.push(`/movie-detail?movieId=${m.id}`)}
                  >
                    <div className="w-full h-28 overflow-hidden relative rounded-lg border border-white bg-primary">
                      <img
                        src={m.img}
                        className="w-full h-full object-cover"
                        alt=""
                      />
                      <div className="absolute top-1.5 left-1.5 bg-ink text-primary w-5 h-5 flex items-center justify-center text-xs font-bold rounded border border-white/30">
                        {idx + 2}
                      </div>
                    </div>
                    <div className="mt-1.5 px-0.5">
                      <h4 className="font-semibold text-sm text-body-dark truncate">{m.nm}</h4>
                      <p className="font-plex text-xs font-bold text-primary mt-0.5">{m.wish}人想看</p>
                    </div>
                  </div>
                ))}
              </div>
            )}

            {/* List 4-8 */}
            <ul className="space-y-4">
              {comingMovies.slice(3).map((m, idx) => (
                <li
                  key={m.id}
                  className="flex justify-between items-center text-sm cursor-pointer group"
                  onClick={() => router.push(`/movie-detail?movieId=${m.id}`)}
                >
                  <div className="flex items-center">
                    <span className="font-plex text-muted mr-3 w-3 text-right">{idx + 4}</span>
                    <span className="text-muted-strong group-hover:text-body-dark transition-colors">{m.nm}</span>
                  </div>
                  <span className="font-plex text-primary text-xs">{m.wish}人想看</span>
                </li>
              ))}
            </ul>
          </div>
        </Reveal>

        {/* TOP 100 */}
        <Reveal delay={0.1}>
          <div className="mb-10">
            <div className="flex justify-between items-end mb-4">
              <h3 className="text-lg font-semibold text-body-dark">TOP 100</h3>
              <a className="flex items-center text-muted-strong hover:text-primary text-xs cursor-pointer transition-colors">
                查看完整榜单 <ChevronRight size={12} />
              </a>
            </div>

            {/* Top 1 — 黄底黑字 + 黑底序号 */}
            <div className="flex gap-3 mb-4 bg-primary p-2.5 rounded-lg border border-white relative cursor-pointer card-lift">
              <div className="absolute top-0 left-0 w-6 h-6 bg-ink text-primary flex items-center justify-center text-xs font-bold z-10 rounded-br-md border border-white/30">
                1
              </div>
              <img
                src="https://picsum.photos/seed/top100/60/80"
                alt=""
                className="w-14 h-[72px] object-cover rounded-md border border-on-primary/20"
              />
              <div className="flex flex-col justify-center flex-1">
                <h4 className="font-semibold text-on-primary">{TOP100[0].title}</h4>
                <p className="font-plex font-bold text-on-primary text-xl mt-1">{TOP100[0].score}分</p>
              </div>
            </div>

            <ul className="space-y-3.5">
              {TOP100.slice(1).map((m) => (
                <li
                  key={m.id}
                  className="flex justify-between items-center text-sm group cursor-pointer"
                >
                  <div className="flex items-center">
                    <span className="font-plex text-muted mr-3 w-3 text-right">{m.id}</span>
                    <span className="text-muted-strong group-hover:text-body-dark transition-colors">{m.title}</span>
                  </div>
                  <span className="font-plex text-primary text-xs font-semibold">
                    {m.score}分
                  </span>
                </li>
              ))}
            </ul>
          </div>
        </Reveal>
      </div>
    </div>
  )
}
