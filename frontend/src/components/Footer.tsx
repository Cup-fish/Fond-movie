/**
 * 浅色 Footer — Binance 标志性反转（footer-light）
 *
 * 深色页面用浅色 footer 收尾，视觉上闭合页面；共享同一套黄色 CTA 与灰蓝描边
 */
export default function Footer() {
  return (
    <footer className="bg-surface-soft text-ink mt-auto min-w-[1200px] border-t border-hairline-light">
      <div className="max-w-[1280px] mx-auto px-4 py-12">
        <div className="grid grid-cols-2 md:grid-cols-4 gap-8">
          {/* 品牌 */}
          <div>
            <div className="flex items-center gap-2 mb-3">
              <div className="w-8 h-8 bg-primary rounded-full flex items-center justify-center">
                <span className="text-on-primary font-bold text-sm">猫</span>
              </div>
              <span className="text-lg font-bold text-primary">猫眼电影</span>
            </div>
            <p className="text-sm text-muted leading-relaxed">娱乐看猫眼，好电影不缺席</p>
          </div>

          {/* 链接列 */}
          <div>
            <h3 className="text-sm font-semibold mb-3">购票指南</h3>
            <ul className="space-y-2 text-sm text-muted">
              <li className="hover:text-ink transition-colors cursor-pointer">电影购票</li>
              <li className="hover:text-ink transition-colors cursor-pointer">影院排片</li>
              <li className="hover:text-ink transition-colors cursor-pointer">演出票务</li>
            </ul>
          </div>
          <div>
            <h3 className="text-sm font-semibold mb-3">用户服务</h3>
            <ul className="space-y-2 text-sm text-muted">
              <li className="hover:text-ink transition-colors cursor-pointer">我的订单</li>
              <li className="hover:text-ink transition-colors cursor-pointer">积分说明</li>
              <li className="hover:text-ink transition-colors cursor-pointer">常见问题</li>
            </ul>
          </div>
          <div>
            <h3 className="text-sm font-semibold mb-3">关于我们</h3>
            <ul className="space-y-2 text-sm text-muted">
              <li className="hover:text-ink transition-colors cursor-pointer">关于猫眼</li>
              <li className="hover:text-ink transition-colors cursor-pointer">加入我们</li>
              <li className="hover:text-ink transition-colors cursor-pointer">联系我们</li>
            </ul>
          </div>
        </div>

        <div className="border-t border-hairline-light mt-10 pt-6 flex items-center justify-between text-xs text-muted">
          <p>Copyright © 2024 猫眼电影</p>
          <p>粤ICP备2025501757号-2</p>
        </div>
      </div>
    </footer>
  )
}
