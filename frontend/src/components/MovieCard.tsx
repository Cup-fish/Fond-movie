'use client'

import { useRouter } from 'next/navigation'
import { motion, useReducedMotion } from 'motion/react'
import { imgUrlReplace } from '@/lib/utils'
import type { MovieItem } from '@/types'

interface MovieCardProps {
  movie: MovieItem
  showButton?: boolean
  index?: number
}

/**
 * 电影卡片 — markets-row 风格（深色卡片 + 交易语义评分）
 *
 * 评分语义：>=8.5 绿（trading-up）· <8 红（trading-down）· 中间 muted
 * 卡片 hover 抬升 + 图片微缩放（状态转换动机）
 */
export default function MovieCard({ movie, showButton = true, index = 0 }: MovieCardProps) {
  const isPlaying = movie.globalReleased
  const reduce = useReducedMotion()
  const router = useRouter()
  const goDetail = () => router.push(`/movie-detail?movieId=${movie.id}`)

  const score = Number(movie.sc)
  const scoreColor =
    !isPlaying || !movie.sc
      ? null
      : score >= 8.5
        ? 'text-trading-up'
        : score < 8
          ? 'text-trading-down'
          : 'text-muted-strong'

  return (
    <motion.div
      initial={reduce ? false : { opacity: 0, y: 20 }}
      animate={{ opacity: 1, y: 0 }}
      whileHover={reduce ? undefined : { y: -4 }}
      transition={{ duration: 0.5, delay: index * 0.05, ease: [0.16, 1, 0.3, 1] }}
      className="w-[160px] flex flex-col mb-6 group cursor-pointer"
    >
      <div className="relative w-full h-[220px] overflow-hidden rounded-lg bg-surface-card border border-hairline-dark card-lift group-hover:border-primary/40 group-hover:shadow-card-dark transition-all duration-300">
        <img
          src={imgUrlReplace(movie.img)}
          alt={movie.nm}
          className="w-full h-full object-cover transition-transform duration-500 group-hover:scale-108"
        />
        {/* 鼠标扫过时光效 */}
        <div className="pointer-events-none absolute inset-0 -translate-x-full bg-gradient-to-r from-transparent via-white/10 to-transparent transition-transform duration-700 group-hover:translate-x-full" />
        {/* Tags Overlay — 左上角标签 */}
        {movie.cat && (
          <div className="absolute top-2 left-2 flex flex-col space-y-1">
            {movie.cat.split(',').slice(0, 2).map((tag, idx) => (
              <span
                key={idx}
                className={`text-[10px] text-on-primary px-1.5 py-0.5 rounded-sm font-semibold shadow-sm ${
                  tag.includes('IMAX') ? 'bg-trading-up text-white' : 'bg-primary'
                }`}
              >
                {tag}
              </span>
            ))}
          </div>
        )}

        {/* 底部渐变：片名 + 评分（等宽数字） */}
        <div className="absolute bottom-0 left-0 w-full bg-gradient-to-t from-black/85 to-transparent p-2.5 pt-8 flex justify-between items-end">
          <span className="text-white text-xs font-medium truncate w-full">{movie.nm}</span>
          {scoreColor && (
            <span className={`font-plex font-bold text-base absolute bottom-1.5 right-2 ${scoreColor}`}>
              {score.toFixed(1)}
            </span>
          )}
        </div>
      </div>

      {/* Below Image Content */}
      {showButton && (
        <div className="mt-2.5 flex items-center justify-between text-sm">
          {isPlaying ? (
            <button
              onClick={goDetail}
              className="w-full py-2 mt-1 bg-primary text-on-primary border border-white rounded-md hover:bg-primary-active transition-all pressable text-sm font-bold"
            >
              购票
            </button>
          ) : (
            <div className="w-full">
              <div className="flex justify-between items-center text-xs text-muted mb-1.5">
                <span className="font-plex text-primary">{movie.wish}人想看</span>
              </div>
              <div className="flex space-x-2">
                <button
                  onClick={goDetail}
                  className="flex-1 py-1.5 border border-hairline-dark text-primary rounded-md hover:bg-primary hover:text-on-primary transition-all pressable text-xs font-medium"
                >
                  预告片
                </button>
                <button
                  onClick={goDetail}
                  className="flex-1 py-1.5 border border-hairline-dark text-muted-strong rounded-md hover:bg-surface-elevated transition-all pressable text-xs"
                >
                  预售
                </button>
              </div>
              {movie.comingTitle && (
                <div className="text-center text-xs text-muted mt-2">{movie.comingTitle}</div>
              )}
            </div>
          )}
        </div>
      )}
    </motion.div>
  )
}
