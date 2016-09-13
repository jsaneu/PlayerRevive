package com.creativemd.playerrevive.server;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.UUID;

import com.creativemd.creativecore.common.packet.PacketHandler;
import com.creativemd.creativecore.gui.opener.GuiHandler;
import com.creativemd.playerrevive.DamageBledToDeath;
import com.creativemd.playerrevive.PlayerRevive;
import com.creativemd.playerrevive.Revival;
import com.creativemd.playerrevive.capability.CapaReviveProvider;
import com.creativemd.playerrevive.packet.PlayerRevivalPacket;
import com.mojang.authlib.GameProfile;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.management.UserListBansEntry;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.datafix.FixTypes;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent.PlayerLoggedInEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent.PlayerLoggedOutEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent.Phase;
import net.minecraftforge.fml.common.gameevent.TickEvent.ServerTickEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import net.minecraftforge.fml.server.FMLServerHandler;
import scala.collection.parallel.ParIterableLike.Min;

public class ReviveEventServer {
	
	private static Boolean isClient = null;
	
	public static boolean isClient()
	{
		if(isClient == null){
			try {
				isClient = Class.forName("net.minecraft.client.Minecraft") != null;
			} catch (ClassNotFoundException e) {
				isClient = false;
			}
		}
		return isClient;
	}
	
	public static boolean isReviveActive()
	{
		if(isClient())
			return !isSinglePlayer();
		return true;
	}
	
	@SideOnly(Side.CLIENT)
	private static boolean isSinglePlayer()
	{
		return Minecraft.getMinecraft().isSingleplayer();
	}
	
	@SubscribeEvent
	public void tick(ServerTickEvent event)
	{
		if(event.phase == Phase.END && isReviveActive())
		{
			ArrayList<UUID> removeFromList = new ArrayList<>();
			
			
			
			for (UUID uuid : PlayerReviveServer.playerRevivals.keySet()) {
				Revival revive = PlayerReviveServer.playerRevivals.get(uuid);
				revive.tick();
				
				EntityPlayer player = FMLServerHandler.instance().getServer().getPlayerList().getPlayerByUUID(uuid);
				if(player != null)
				{
					player.getFoodStats().setFoodLevel(PlayerRevive.playerFoodAfter);
					player.setHealth(PlayerRevive.playerHealthAfter);
				}
				
				if(revive.isRevived() || revive.isDead())
				{
					
					removeFromList.add(uuid);
					
					
					if(player == null)
					{
						//TODO Modify player save data
					}else{
						if(revive.isDead())
						{
							player.setHealth(0.0F);
							player.onDeath(DamageBledToDeath.bledToDeath);
							
						}
						for (int i = 0; i < revive.revivingPlayers.size(); i++) {
							revive.revivingPlayers.get(i).closeScreen();
						}
						PacketHandler.sendPacketToPlayer(new PlayerRevivalPacket(null), (EntityPlayerMP) player);
					}
					
					if(revive.isDead() && PlayerRevive.banPlayerAfterDeath)
					{
						GameProfile profile = null;
						if(player == null)
							profile = new GameProfile(uuid, "");
						else
							profile = player.getGameProfile();
						FMLServerHandler.instance().getServer().getPlayerList().getBannedPlayers().addEntry(new UserListBansEntry(player.getGameProfile()));
						try {
							FMLServerHandler.instance().getServer().getPlayerList().getBannedPlayers().writeChanges();
						} catch (IOException e) {
							e.printStackTrace();
						}
					}
				}
			}
			for (int i = 0; i < removeFromList.size(); i++) {
				PlayerReviveServer.playerRevivals.remove(removeFromList.get(i));
			}
		}		
	}
	
	@SubscribeEvent
	public void playerLeave(PlayerLoggedOutEvent event)
	{
		PlayerReviveServer.removePlayerAsHelper(event.player);
	}
	
	@SubscribeEvent
	public void playerJoin(PlayerLoggedInEvent event)
	{
		if(!isReviveActive())
			return ;
		Revival revive = PlayerReviveServer.playerRevivals.get(EntityPlayer.getUUID(event.player.getGameProfile()));
		if(revive != null)
			PacketHandler.sendPacketToPlayer(new PlayerRevivalPacket(revive), (EntityPlayerMP) event.player);
	}
	
	@SubscribeEvent
	public void playerInteract(PlayerInteractEvent.EntityInteract event)
	{
		if(!PlayerReviveServer.isPlayerBleeding(event.getEntityPlayer()) && event.getTarget() instanceof EntityPlayer)
		{
			EntityPlayer player = (EntityPlayer) event.getTarget();
			Revival revive = PlayerReviveServer.getRevival(player);
			if(revive != null)
			{
				NBTTagCompound nbt = new NBTTagCompound();
				nbt.setString("uuid", EntityPlayer.getUUID(player.getGameProfile()).toString());
				revive.revivingPlayers.add(event.getEntityPlayer());
				GuiHandler.openGui("plreviver", nbt, event.getEntityPlayer());
				//System.out.println("OPEN GUI!");
			}
		}
	}
	
	@SubscribeEvent
	public void playerDamage(LivingHurtEvent event)
	{
		if(event.getEntityLiving() instanceof EntityPlayer)
		{
			EntityPlayer player = (EntityPlayer) event.getEntityLiving();
			if(PlayerReviveServer.isPlayerBleeding(player) && event.getSource() != DamageBledToDeath.bledToDeath)
				event.setCanceled(true);
		}
	}
	
	@SubscribeEvent
	public void playerDied(LivingDeathEvent event)
	{
		if(event.getEntityLiving() instanceof EntityPlayer && isReviveActive())
		{
			EntityPlayer player = (EntityPlayer) event.getEntityLiving();
			UUID uuid = EntityPlayer.getUUID(player.getGameProfile());
			Revival revive = PlayerReviveServer.playerRevivals.get(uuid);
			if(revive == null)
			{
				revive = new Revival();
				PlayerReviveServer.playerRevivals.put(uuid, revive);
				PacketHandler.sendPacketToPlayer(new PlayerRevivalPacket(revive), (EntityPlayerMP) player);
			}
			if(!revive.isDead())
			{
				event.setCanceled(true);
				player.setHealth(0.5F);
				player.getFoodStats().setFoodLevel(1);
			}
		}
		//Revival revive2 = ((EntityPlayer) event.getEntityLiving()).getCapability(Revival.reviveCapa, null);
	}
	
	@SubscribeEvent
	public void attachCapability(AttachCapabilitiesEvent.Entity event)
	{
		if(event.getEntity() instanceof EntityPlayer)
		{
			event.addCapability(new ResourceLocation(PlayerRevive.modid, "revive"), new CapaReviveProvider());
		}
	}
	
}
