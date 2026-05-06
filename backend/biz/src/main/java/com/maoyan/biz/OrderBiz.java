package com.maoyan.biz;

import com.maoyan.domain.model.dto.CreateOrderDTO;
import com.maoyan.domain.model.po.SchedulePO;
import com.maoyan.domain.model.vo.OrderVO;
import com.maoyan.service.MovieService;
import com.maoyan.service.OrderService;
import com.maoyan.service.ScheduleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderBiz {

    private final OrderService orderService;
    private final ScheduleService scheduleService;
    private final MovieService movieService;

    public OrderVO createOrder(Long userId, CreateOrderDTO dto) {
        OrderVO orderVO = orderService.createOrder(userId, dto);

        try {
            SchedulePO schedule = scheduleService.getById(dto.getScheduleId());
            if (schedule != null) {
                var movieDetail = movieService.getMovieDetail(schedule.getMovieId());
                if (movieDetail != null) {
                    orderVO.setMovieName(movieDetail.getNm());
                    orderVO.setMovieImg(movieDetail.getImg());
                }
            }
        } catch (Exception e) {
            log.warn("Failed to enrich order snapshot", e);
        }

        return orderVO;
    }

    public void cancelOrder(Long userId, String orderNo) {
        orderService.cancelOrder(userId, orderNo);
    }

    public List<OrderVO> getUserOrders(Long userId, int page, int size) {
        return orderService.getUserOrders(userId, page, size);
    }
}
