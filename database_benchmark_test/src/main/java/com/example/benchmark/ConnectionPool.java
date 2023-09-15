package com.example.benchmark;/*
 * Copyright (C) 2021, 2023, THL A29 Limited, a Tencent company. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 */

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class ConnectionPool {
    private static Lock lock = new ReentrantLock();

    private static ConnectionNode head = null;
    private static final int connectionCount = 16;

    public static ConnectionNode getConnection() {
        lock.lock();
        try {
            if (head != null) {
                ConnectionNode target = head;
                head = head.next;
                return target;
            }
        } finally {
            lock.unlock();
        }

        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        return null;
    }

    public static void releaseConnection(ConnectionNode node) {
        addConnectionNode(node);
    }

    private static void addConnectionNode(ConnectionNode current) {
        lock.lock();
        try {
            current.next = head;
            head = current;
        } finally {
            lock.unlock();
        }
    }

    public static void initConnectionPool() {
        try {
            for (int i = 0; i < connectionCount; i++) {
                ConnectionNode current = new ConnectionNode();
                addConnectionNode(current);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void closeConnection() {
        try {
            for (int i = 0; i < connectionCount; i++) {
                ConnectionNode node = getConnection();
                if (node != null) {
                    try {
                        if (node.stm != null) {
                            node.stm.close();
                        }

                        if (node.con != null) {
                            node.con.close();
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}