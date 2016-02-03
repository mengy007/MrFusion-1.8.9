package com.techmafia.mcmods.mrfusion.tileentity;

import cofh.api.energy.EnergyStorage;
import cofh.api.energy.IEnergyHandler;
import com.techmafia.mcmods.mrfusion.net.CommonPacketHandler;
import com.techmafia.mcmods.mrfusion.net.messages.DeviceUpdateMessage;
import moze_intel.projecte.api.ProjectEAPI;
import moze_intel.projecte.api.item.IItemEmc;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S35PacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.*;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;

import java.util.HashSet;
import java.util.Set;

/**
 * Created by mengy007 on 1/31/2016.
 */
public class TileEntityMrFusion extends TileEntity implements ITickable, IEnergyHandler, IInventory {
    final int NUMBER_OF_SLOTS = 1;
    final String DISPLAY_NAME = "Mr. Fusion";

    protected EnergyStorage energyStorage = new EnergyStorage(1000000);

    private int ticksSinceLastUpdate = 0;
    private int ticksBetweenUpdates = 3;
    private ItemStack itemStack;
    private Set<EntityPlayer> playersWatching;

    public TileEntityMrFusion() {
        playersWatching = new HashSet<EntityPlayer>();
    }

    public TileEntityMrFusion(int capacity) {
        this.energyStorage = new EnergyStorage(capacity);
        playersWatching = new HashSet<EntityPlayer>();
    }

    /* Server sync */
    @Override
    public Packet getDescriptionPacket() {
        NBTTagCompound nbtTagCompound = new NBTTagCompound();
        writeToNBT(nbtTagCompound);
        int metadata = getBlockMetadata();
        return new S35PacketUpdateTileEntity(this.pos, metadata, nbtTagCompound);
    }

    @Override
    public void onDataPacket(NetworkManager net, S35PacketUpdateTileEntity pkt) {
        readFromNBT(pkt.getNbtCompound());
    }

    public void beginUpdatingPlayer(EntityPlayer player) {
        playersWatching.add(player);
        sendUpdatePacketToPlayer(player);
    }

    public void stopUpdatingPlayer(EntityPlayer player) {
        playersWatching.remove(player);
    }

    private void sendUpdatePacketToPlayer(EntityPlayer player) {
        if (this.worldObj.isRemote) { return; }

        CommonPacketHandler.INSTANCE.sendTo(getUpdatePacket(), (EntityPlayerMP)player);
    }

    private void sendUpdatePacket() {
        if (this.worldObj.isRemote) { return; }
        if (this.playersWatching.size() <= 0) { return; }

        for (EntityPlayer player : playersWatching) {
            CommonPacketHandler.INSTANCE.sendTo(getUpdatePacket(), (EntityPlayerMP)player);
        }
    }

    protected IMessage getUpdatePacket() {
        NBTTagCompound childData = new NBTTagCompound();

        onSendUpdate(childData);

        return new DeviceUpdateMessage(pos.getX(), pos.getY(), pos.getZ(), childData);
    }

    /**
     * Sets information to send
     * @param nbt
     */
    public void onSendUpdate(NBTTagCompound nbt) {
        NBTTagCompound energyTag = new NBTTagCompound();
        this.energyStorage.writeToNBT(energyTag);
        nbt.setTag("energyStorage", energyTag);
    }

    /**
     * Called on received update packet from server
     * @param nbt
     */
    public void onReceiveUpdate(NBTTagCompound nbt) {
        this.energyStorage.readFromNBT(nbt.getCompoundTag("energyStorage"));
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        super.readFromNBT(nbt);

        /* IEnergyContainer */
        if (nbt.hasKey("energyStorage")) {
            this.energyStorage.readFromNBT(nbt.getCompoundTag("energyStorage"));
        }

        /* IInventory */
        final byte NBT_TYPE_COMPOUND = 10;       // See NBTBase.createNewByType() for a listing
        NBTTagList dataForAllSlots = nbt.getTagList("Items", NBT_TYPE_COMPOUND);

        itemStack = null;
        NBTTagCompound dataForOneSlot = dataForAllSlots.getCompoundTagAt(0);
        this.itemStack = ItemStack.loadItemStackFromNBT(dataForOneSlot);
    }

    @Override
    public void writeToNBT(NBTTagCompound nbt) {
        super.writeToNBT(nbt);

        /* IEnergyContainer */
        NBTTagCompound energyTag = new NBTTagCompound();
        this.energyStorage.writeToNBT(energyTag);
        nbt.setTag("energyStorage", energyTag);

        /* IInventory */
        if (itemStack != null) {
            NBTTagList dataForAllSlots = new NBTTagList();
            NBTTagCompound dataForThisSlot = new NBTTagCompound();
            dataForThisSlot.setByte("Slot", (byte) 0);
            this.itemStack.writeToNBT(dataForThisSlot);
            dataForAllSlots.appendTag(dataForThisSlot);

            nbt.setTag("Items", dataForAllSlots);
        }
    }

    /* ITickable */
    public void update() {
        if (!this.hasWorldObj()) return;
        World world = this.getWorld();
        if (world.isRemote) {
            return; // Don't do anything on client side
        }

        ItemStack itemStack = getStackInSlot(0);

        // Only run if there are items to burn and energy is not full
        if (itemStack != null && itemStack.stackSize > 0 && this.getEnergyStored(null) < this.getMaxEnergyStored(null)) {
            int rfPerItem = 1;

            // munch munch much one stack at a time
            for (int i=0; i<itemStack.stackSize; i++) {
                // Break if energy is full or itemStack is depleted
                if (this.getEnergyStored(null) == this.getMaxEnergyStored(null) || itemStack == null) {
                    break;
                }

                // 1 item at a time
                ItemStack removedItemStack = decrStackSize(0, 1);
                if (removedItemStack != null && removedItemStack.stackSize == 1) {
                    // EMC support
                    if (ProjectEAPI.getEMCProxy().hasValue(removedItemStack)) {
                        rfPerItem = ProjectEAPI.getEMCProxy().getValue(removedItemStack);
                    }
                    this.receiveEnergy(null, rfPerItem, false);
                }
            }
        }

        // Send update to players watching
        if (this.playersWatching.size() > 0) {
            ticksSinceLastUpdate++;
            if (ticksSinceLastUpdate >= ticksBetweenUpdates) {
                sendUpdatePacket();
                ticksSinceLastUpdate = 0;
            }
        }
    }

    /* IWorldNameable */
    @Override
    public String getName() {
        return DISPLAY_NAME;
    }

    @Override
    public boolean hasCustomName() {
        return true;
    }

    @Override
    public IChatComponent getDisplayName() {
        return (IChatComponent)(this.hasCustomName() ? new ChatComponentText(this.getName()) : new ChatComponentTranslation(this.getName(), new Object[0]));
    }

    /* IInventory */
    // Gets the number of slots in the inventory
    @Override
    public int getSizeInventory() {
        return NUMBER_OF_SLOTS;
    }

    // Gets the stack in the given slot
    @Override
    public ItemStack getStackInSlot(int slotIndex) {
        return itemStack;
    }

    /**
     * Removes some of the units from itemstack in the given slot, and returns as a separate itemstack
     * @param slotIndex the slot number to remove the items from
     * @param count the number of units to remove
     * @return a new itemstack containing the units removed from the slot
     */
    @Override
    public ItemStack decrStackSize(int slotIndex, int count) {
        ItemStack itemStackInSlot = getStackInSlot(slotIndex);
        if (itemStackInSlot == null) return null;

        ItemStack itemStackRemoved;
        if (itemStackInSlot.stackSize <= count) {
            itemStackRemoved = itemStackInSlot;
            setInventorySlotContents(slotIndex, null);
        } else {
            itemStackRemoved = itemStackInSlot.splitStack(count);
            if (itemStackInSlot.stackSize == 0) {
                setInventorySlotContents(slotIndex, null);
            }
        }
        markDirty();
        return itemStackRemoved;
    }

    // overwrites the stack in the given slotIndex with the given stack
    @Override
    public void setInventorySlotContents(int slotIndex, ItemStack itemstack) {
        itemStack = itemstack;
        if (itemstack != null && itemstack.stackSize > getInventoryStackLimit()) {
            itemstack.stackSize = getInventoryStackLimit();
        }
        markDirty();
    }

    // This is the maximum number if items allowed in each slot
    // This only affects things such as hoppers trying to insert items you need to use the container to enforce this for players
    // inserting items via the gui
    @Override
    public int getInventoryStackLimit() {
        return 64;
    }

    @Override
    public ItemStack removeStackFromSlot(int slot) {
        return itemStack;
    }

    // Return true if the given player is able to use this block. In this case it checks that
    // 1) the world tileentity hasn't been replaced in the meantime, and
    // 2) the player isn't too far away from the centre of the block
    @Override
    public boolean isUseableByPlayer(EntityPlayer player) {
        if (this.worldObj.getTileEntity(this.pos) != this) return false;
        final double X_CENTRE_OFFSET = 0.5;
        final double Y_CENTRE_OFFSET = 0.5;
        final double Z_CENTRE_OFFSET = 0.5;
        final double MAXIMUM_DISTANCE_SQ = 8.0 * 8.0;
        return player.getDistanceSq(pos.getX() + X_CENTRE_OFFSET, pos.getY() + Y_CENTRE_OFFSET, pos.getZ() + Z_CENTRE_OFFSET) < MAXIMUM_DISTANCE_SQ;
    }

    // Return true if the given stack is allowed to go in the given slot.  In this case, we can insert anything.
    // This only affects things such as hoppers trying to insert items you need to use the container to enforce this for players
    // inserting items via the gui
    @Override
    public boolean isItemValidForSlot(int slotIndex, ItemStack itemstack) {
        return true;
    }

    @Override
    public void openInventory(EntityPlayer player) {}

    @Override
    public void closeInventory(EntityPlayer player) {}

    @Override
    public int getField(int id) {
        return 0;
    }

    @Override
    public void setField(int id, int value) {}

    @Override
    public int getFieldCount() {
        return 0;
    }

    @Override
    public void clear() {
        itemStack = null;
    }

    /* IEnergyHandler */
    @Override
    public int receiveEnergy(EnumFacing from, int maxReceive, boolean simulate) {
        return energyStorage.receiveEnergy(maxReceive, simulate);
    }

    @Override
    public int extractEnergy(EnumFacing from, int maxExtract, boolean simulate) {
        return energyStorage.extractEnergy(maxExtract, simulate);
    }

    @Override
    public int getMaxEnergyStored(EnumFacing from) {
        return energyStorage.getMaxEnergyStored();
    }

    @Override
    public int getEnergyStored(EnumFacing from) {
        return energyStorage.getEnergyStored();
    }

    @Override
    public boolean canConnectEnergy(EnumFacing from) {
        return true;
    }

}
