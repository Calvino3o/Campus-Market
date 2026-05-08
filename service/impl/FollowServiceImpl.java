package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserService userService;

    // 关注或取关   +   共同关注
    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        // 0. 获取登录用户
        Long userId = UserHolder.getUser().getId();
        String key = "follows:" + userId;
        // 判断是要关注还是取关
        // 1. 关注
        if (isFollow) {
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            boolean isSuccess = save(follow);
            // 如果成功，写入redis
            if (isSuccess) stringRedisTemplate.opsForSet().add(key, followUserId.toString());
        } else {
            // 2. 取关
            // 删除
            boolean isSuccess = remove(new QueryWrapper<Follow>()
                    .eq("user_id", userId).eq("follow_user_id", followUserId));
            if (isSuccess) stringRedisTemplate.opsForSet().remove(key, followUserId.toString());
        }
        return Result.ok();
    }

    // 查询是否关注
    @Override
    public Result isFollow(Long followUserId) {
        // 0. 获取登录用户
        Long userId = UserHolder.getUser().getId();
        // 1. 查询数据库，判断是否关注
        Integer count = query()
                .eq("user_id", userId).eq("follow_user_id", followUserId).count();
        // 2. 能查到则关注了，反之未关注
        return Result.ok(count > 0);
    }

    // 查询共同关注 core: interset
    @Override
    public Result followCommons(Long followUserId) {
        // 1. 获取当前用户id
        Long userId = UserHolder.getUser().getId();
        String key = "follows:" + userId;
        // 2. 求交集
        // 2.1 获取目标用户id
        String key2 = "follows:" + followUserId;
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key, key2);
        // 2.2 判断结果为空，直接返回空List
        if (intersect == null || intersect.isEmpty()) return Result.ok(Collections.emptyList());
        // 3. 解析id集合
        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        // 4. 查询用户
        List<UserDTO> commonusers = userService.listByIds(ids)
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        // 5. 返回用户列表
        return Result.ok(commonusers);
    }
}
