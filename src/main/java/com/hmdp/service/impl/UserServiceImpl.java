package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexPatterns;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 校验手机号格式, 若不符合，返回错误码
        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号格式错误！");//前后端都需要校验格式
        }
        // 发送验证码
        String code = RandomUtil.randomNumbers(6);
        // 保存验证码到session
        session.setAttribute("code", code);
        //发送验证码
        // TODO 需要短信发送平台等
        log.debug("发送短信验证码成功，验证码为：" + code);
        //返回OK
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 校验手机号
        String phone = loginForm.getPhone();
        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号格式不正确 ");
        }
        // 校验验证码
        Object cacheCode = session.getAttribute("code");
        String code = loginForm.getCode();
        // 不一致，报错
        if(cacheCode == null || !cacheCode.equals(code)){
            return Result.fail("验证码错误！");
        }
        // 一致，从数据库查询用户
        User user = query().eq("phone", phone).one();

        //用户不存在，创建新用户并保存
        if(user == null){
            user = createUserWithPhone(phone);
        }
        log.debug(user.toString());
        //保存用户信息到session
        //需要隐藏敏感信息，仅保存必要信息至session
        session.setAttribute("user", BeanUtil.copyProperties(user, UserDTO.class));
        return Result.ok(user);
    }

    private User createUserWithPhone(String phone) {
        // 创建用户
        User user = new User();
        user.setPhone(phone);
        user.setNickName(SystemConstants.USER_NICK_NAME_PREFIX +RandomUtil.randomString(10));
        // 保存用户
        save(user);
        return user;
    }
}
