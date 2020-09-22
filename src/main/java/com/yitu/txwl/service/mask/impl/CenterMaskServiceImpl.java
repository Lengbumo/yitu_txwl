package com.yitu.txwl.service.mask.impl;

import com.alibaba.fastjson.JSONObject;
import com.yitu.txwl.core.util.RedisUtil;
import com.yitu.txwl.entity.CenterMask;
import com.yitu.txwl.pojo.CenterMaskPojo;
import com.yitu.txwl.service.mask.CenterMaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 国展中心口罩指数
 *
 * @author WJ
 * @version 1.0.0
 * @date 2020-09-22 11:28
 */
@Service
public class CenterMaskServiceImpl implements CenterMaskService {
    private Logger log = LoggerFactory.getLogger(this.getClass());

    @Value("${fetchTrack.filePath}")
    private String filePath;
    @Autowired
    private RedisUtil redisUtil;

    @Override
    @Scheduled(cron = "0 0/5 * * * ?")
    public void execFetchTrackMeta() {
        // 初始化工具地址
        initFilePath();

        // 修改接口工具配置文件
        updateFetchTrackConf();

        // 缓存当前调用时间戳 10位
        Long milli = LocalDateTime.now().toEpochSecond(ZoneOffset.of("+8"));
        redisUtil.set("fetch_track_start_time", milli);

        // 执行脚本之前先清空pedestrian_meta目录下文件
        deleteFiles();

        // 执行shell脚本
         execShell();

        // 读取pedestrian_meta文件下的meta文件并缓存结果
        readFile();
    }

    @Override
    public List<CenterMaskPojo> getCenterMaskData() {
        // 先从redis获取数据
        List<CenterMaskPojo> list = (List<CenterMaskPojo>) redisUtil.get("center_mask");
        // 如果redis为空，则去读取接口文件目录meta下数据
        if (list.isEmpty()) {
            list = readFile();
            // 如果依然为空， 则重新调用定时任务，获取meta数据
            if (list.isEmpty()) {
                execFetchTrackMeta();
                list = readFile();
            }
        }

        return list;
    }

    /**
     * 初始化接口工具目录地址
     * 如果impl配置了地址则使用配置文件的地址
     * 否则默认使用项目同级目录下的fetch_track地址
     */
    private void initFilePath() {
        String path = System.getProperty("user.dir") + File.separator + "fetch_track";
        filePath = StringUtils.isEmpty(filePath) ? path : filePath;
    }

    /**
     * 修改接口工具配置文件
     */
    private void updateFetchTrackConf() {
        String path = filePath + File.separator + "fetch_track_tool" + File.separator + "fetch_track_meta.conf";
        Integer startTime = (Integer) redisUtil.get("fetch_track_start_time");
        // 如果redis缓存中不存在说明还未调用过接口工具
        // 不修改配置文件
        if (null == startTime) {
            return;
        }
        BufferedWriter bw = null;
        BufferedReader br = null;
        String line;
        //保存修改过后的所有内容，不断增加
        try {
            File file = new File(path);
            if (!file.exists()) {
                throw new Exception("配置文件不存在");
            }
            br = new BufferedReader(new FileReader(path));
            StringBuffer buf = new StringBuffer();
            while ((line = br.readLine()) != null) {
                //修改内容核心代码
                if (line.contains("start_time")) {
                    String[] split = line.split(":");
                    // 将配置的开始时间替换为上次的时间戳
                    String replace = line.replace(split[1], startTime.toString() + ",");
                    buf.append(replace);
                } else {
                    buf.append(line);
                }
                buf.append(System.getProperty("line.separator"));
            }
            bw = new BufferedWriter(new FileWriter(path));
            bw.write(buf.toString().toCharArray());
            bw.flush();
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        } finally {
            if (bw != null) {
                try {
                    bw.close();
                } catch (IOException e) {
                    bw = null;
                }
            }
            if (br != null) {
                try {
                    br.close();
                } catch (IOException e) {
                    br = null;
                }
            }
        }
    }

    /**
     * 执行sh脚本
     */
    private void execShell() {
        String path = filePath + File.separator + "run_fetch_track.sh";
        String command = "sh " + path;
        Process pid;
        try {
            // 执行linux sh命令
            pid = Runtime.getRuntime().exec(command);
            if (null != pid) {
                // 等待进程结束
                pid.waitFor();
            } else {
                log.error("execShell---------------> 没有pid");
            }
            log.info("execShell---------------> sh脚本执行完成");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 读取文件
     */
    private void deleteFiles() {
        String path = filePath + File.separator + "pedestrian_meta";
        File dirFile = new File(path);
        File[] files = dirFile.listFiles();
        if (null != files && files.length > 0) {
            for (File file : files) {
                file.delete();
            }
        }
    }

    /**
     * 读取文件并缓存结果
     */
    private List<CenterMaskPojo> readFile() {
        String path = filePath + File.separator + "pedestrian_meta";
        File dirFile = new File(path);
        File[] files = dirFile.listFiles();
        LinkedHashMap<Integer, CenterMask> map = new LinkedHashMap<>();
        if (null != files && files.length > 0) {
            BufferedReader br = null;
            String line;
            for (File file : files) {
                try {
                    if (file.isFile()) {
                        br = new BufferedReader(new FileReader(file));
                        while ((line = br.readLine()) != null) {
                            // 每一行是一个行人数据
                            JSONObject object = JSONObject.parseObject(line);
                            Integer cameraId = (Integer) object.get("camera_id");
                            Integer recMask = (Integer) object.get("rec_mask");
                            CenterMask data = map.getOrDefault(cameraId, new CenterMask());
                            data.setCameraId(cameraId);
                            data.setTotalFace(data.getTotalFace() + 1);
                            // rec_mask 1是戴口罩，0是不戴口罩，-1是未知
                            if (null != recMask && recMask == 1) {
                                data.setMaskFace(data.getMaskFace() + 1);
                            }
                            map.put(cameraId, data);
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    if (br != null) {
                        try {
                            br.close();
                        } catch (IOException e) {
                            br = null;
                        }
                    }
                }
            }
        }

        // 读完meta数据后清空
        deleteFiles();

        // 计算每个摄像头的口罩指数
        map.forEach((k, v) -> {
            BigDecimal proportion = new BigDecimal(v.getMaskFace()).multiply(new BigDecimal(100))
                    .divide(new BigDecimal(v.getTotalFace()), BigDecimal.ROUND_HALF_UP);
            v.setMaskProportion(proportion.intValue());
        });
        // 根据口罩指数排序
        // 取前4
        // 并转为List
        List<CenterMaskPojo> list = map.entrySet().stream()
                .sorted(Map.Entry.comparingByValue())
                .limit(4)
                .map(e -> {
                    CenterMaskPojo pojo = new CenterMaskPojo();
                    pojo.setId(e.getValue().getCameraId());
                    pojo.setValue(e.getValue().getMaskProportion());
                    return pojo;
                })
                .collect(Collectors.toList());

        // 将结果缓存到redis
        if (!list.isEmpty()) {
            redisUtil.set("center_mask", list);
        }
        return list;
    }

}
