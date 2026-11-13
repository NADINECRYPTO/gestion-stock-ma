package ma.gestion.stock.data

import android.content.Context
import androidx.room.*
import androidx.sqlite.db.SupportSQLiteDatabase

class Converters {
    @TypeConverter fun moveType(v: MoveType) = v.name
    @TypeConverter fun moveType(v: String) = MoveType.valueOf(v)
    @TypeConverter fun docType(v: DocType?) = v?.name
    @TypeConverter fun docType(v: String?) = v?.let { DocType.valueOf(it) }
    @TypeConverter fun docStatus(v: DocStatus) = v.name
    @TypeConverter fun docStatus(v: String) = DocStatus.valueOf(v)
}

@Database(
    entities = [Client::class, Article::class, StockMove::class, Document::class, DocLine::class,
        Payment::class, Counter::class, Company::class],
    version = 1, exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun clients(): ClientDao
    abstract fun articles(): ArticleDao
    abstract fun moves(): StockMoveDao
    abstract fun documents(): DocumentDao
    abstract fun payments(): PaymentDao
    abstract fun misc(): MiscDao

    companion object {
        const val NAME = "gestion.db"

        fun build(ctx: Context): AppDatabase =
            Room.databaseBuilder(ctx, AppDatabase::class.java, NAME)
                .addCallback(object : Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        // Client Comptoir par défaut + fiche société vide
                        db.execSQL("INSERT INTO clients(name, phone, address, city, ice, isCounter) VALUES('Comptoir','','','','',1)")
                        db.execSQL("INSERT INTO company(id,name,address,city,phone,email,ice,ifNumber,rc,bank,vatRates) VALUES(1,'','','','','','','','','','0;7;10;14;20')")
                    }
                })
                .build()
    }
}
